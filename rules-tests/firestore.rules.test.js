// Security rules regression tests for firestore.rules.
//
// The rules are the app's only enforced security boundary. They run on Firestore's servers, not in
// the app process, so none of the Kotlin tests can observe them: every assertion in this file is a
// claim about the deployed rules that would otherwise only be checked by hand, once, against a
// live project. Each test here maps to a change that was made because the previous rule was wrong.
//
// Run with:  npm run test:rules:emulated
//
// Firebase publishes rules-unit-testing for Node only, so this harness sits outside the Android
// Gradle build. It is wired into CI by .github/workflows/verify.yml.

const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert");
const { readFileSync } = require("node:fs");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const {
  doc,
  setDoc,
  getDoc,
  getDocs,
  updateDoc,
  deleteDoc,
  collection,
  collectionGroup,
  query,
  where,
} = require("firebase/firestore");

const OWNER = "owner-uid";
const EDITOR = "editor-uid";
const VIEWER = "viewer-uid";
const STRANGER = "stranger-uid";
const DOC_ID = "doc-1";
const DOC_PATH = ["users", OWNER, "documents", DOC_ID];

let env;

const asUser = (uid) => env.authenticatedContext(uid, { email: `${uid}@example.com` });

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "paper-docs-rules-test",
    firestore: { rules: readFileSync("firestore.rules", "utf8") },
  });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  // Seed with rules disabled. A well-formed document the owner has shared with an editor and a
  // viewer, plus the presence and share-index rows the app writes alongside it.
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, ...DOC_PATH), {
      localId: DOC_ID,
      title: "Secret plans",
      content: "the original text",
      marks: [],
      updatedAt: 1,
      origin: OWNER,
      sharedWith: { [EDITOR]: "EDITOR", [VIEWER]: "VIEWER" },
    });
    await setDoc(doc(db, ...DOC_PATH, "sharedWith", EDITOR), {
      uid: EDITOR, role: "EDITOR", title: "Secret plans",
    });
    await setDoc(doc(db, ...DOC_PATH, "sharedWith", VIEWER), {
      uid: VIEWER, role: "VIEWER", title: "Secret plans",
    });
    await setDoc(doc(db, ...DOC_PATH, "presence", EDITOR), {
      uid: EDITOR, name: "Editor", photoUrl: null, viewingAt: 10,
    });
  });
});

// ---------------------------------------------------------------- editor writes

test("editor can change document content", async () => {
  await assertSucceeds(
    updateDoc(doc(asUser(EDITOR).firestore(), ...DOC_PATH), { content: "edited by the editor" })
  );
});

test("editor cannot change the access control list", async () => {
  // This is the escalation the affectedKeys() restriction exists to stop: an editor who can write
  // sharedWith can grant themselves access to a document that was later taken away, or hand it to
  // someone the owner never approved.
  await assertFails(
    updateDoc(doc(asUser(EDITOR).firestore(), ...DOC_PATH), {
      sharedWith: { [EDITOR]: "EDITOR", [STRANGER]: "EDITOR" },
    })
  );
});

test("editor cannot grant themselves a permanent role after being revoked", async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), ...DOC_PATH), {
      localId: DOC_ID, title: "Secret plans", content: "text", marks: [], updatedAt: 2,
      origin: OWNER, sharedWith: {}, // the owner revoked the editor
    });
  });
  // Not an editor any more, so even a content-only write must be refused.
  await assertFails(
    updateDoc(doc(asUser(EDITOR).firestore(), ...DOC_PATH), { content: "sneaking in" })
  );
});

test("viewer cannot write the document at all", async () => {
  await assertFails(
    updateDoc(doc(asUser(VIEWER).firestore(), ...DOC_PATH), { content: "read-only, thanks" })
  );
});

test("stranger cannot read the document", async () => {
  await assertFails(getDoc(doc(asUser(STRANGER).firestore(), ...DOC_PATH)));
});

// ---------------------------------------------------------------- index leaks
//
// Firestore authorizes a document against the rules on that document alone, never against its
// parents. An open `allow read: if signedIn()` on a subcollection therefore leaks that
// subcollection to every signed-in user who knows the parent path -- and a collectionGroup() query
// is authorized against the documents it returns, never their ancestors, so the same open rule
// leaks the entire application's sharing graph in one query.

test("outsider cannot read the share index of a document they cannot open", async () => {
  // The share entry carries the document title, so this leaks document names, not just uids.
  await assertFails(getDoc(doc(asUser(STRANGER).firestore(), ...DOC_PATH, "sharedWith", EDITOR)));
});

test("outsider cannot read the presence list of a document they cannot open", async () => {
  // The presence row carries a display name and a photo URL.
  await assertFails(getDoc(doc(asUser(STRANGER).firestore(), ...DOC_PATH, "presence", EDITOR)));
});

test("recipient can read their own share entry", async () => {
  // The other half of the previous restriction: a collaborator must still find what was shared
  // with them. This is the query behind CloudSync.sharedWithMe().
  const seen = await assertSucceeds(
    getDocs(
      query(
        collectionGroup(asUser(VIEWER).firestore(), "sharedWith"),
        where("uid", "==", VIEWER)
      )
    )
  );
  assert.strictEqual(seen.docs.length, 1);
  assert.strictEqual(seen.docs[0].id, VIEWER);
});

test("recipient can delete their own share entry to hide a shared document", async () => {
  // Backs CloudSync.hideShared(), which the owner-only write rule on the subcollection otherwise
  // blocks for the recipient.
  await assertSucceeds(
    deleteDoc(doc(asUser(VIEWER).firestore(), ...DOC_PATH, "sharedWith", VIEWER))
  );
});

test("stranger cannot read the whole sharing graph with a collection group query", async () => {
  await assertFails(
    getDocs(collectionGroup(asUser(STRANGER).firestore(), "sharedWith"))
  );
});

// ---------------------------------------------------------------- allow cases
//
// These are the counterpart to the leak tests above, and they matter just as much. An earlier
// revision of these rules recovered the owner from the {path=**} wildcard using path.size(), which
// is not a function Firestore rules provide. The call raised a runtime error, and Firestore treats
// a rule error as a denial, so every rule that used it silently denied *all* access -- including
// the legitimate reads below. Every denial test in the file kept passing throughout, which is why
// the breakage was invisible until these were added.

test("owner can read the share index of their own document", async () => {
  const entries = await assertSucceeds(
    getDocs(collection(asUser(OWNER).firestore(), ...DOC_PATH, "sharedWith"))
  );
  assert.deepStrictEqual(entries.docs.map((d) => d.id).sort(), [EDITOR, VIEWER]);
});

test("owner can read the presence list of their own document", async () => {
  const seen = await assertSucceeds(
    getDocs(collection(asUser(OWNER).firestore(), ...DOC_PATH, "presence"))
  );
  assert.strictEqual(seen.docs.length, 1);
  assert.strictEqual(seen.docs[0].id, EDITOR);
});

test("collaborator can read the presence list of a document shared with them", async () => {
  // This is the live collaboration feature: the avatars beside the text come from this read.
  const seen = await assertSucceeds(
    getDocs(collection(asUser(VIEWER).firestore(), ...DOC_PATH, "presence"))
  );
  assert.strictEqual(seen.docs.length, 1);
  assert.strictEqual(seen.docs[0].id, EDITOR);
});

test("a collaborator can publish their own presence row", async () => {
  await assertSucceeds(
    setDoc(doc(asUser(VIEWER).firestore(), ...DOC_PATH, "presence", VIEWER), {
      uid: VIEWER, name: "Viewer", photoUrl: null, viewingAt: 20,
    })
  );
});

test("owner can clear another user's presence row when deleting a document", async () => {
  // deleteCloudDocument() sweeps the presence subcollection. If the owner could not delete rows
  // addressed to other people, those rows would be stranded behind a deleted document.
  await assertSucceeds(
    deleteDoc(doc(asUser(OWNER).firestore(), ...DOC_PATH, "presence", EDITOR))
  );
});

test("a stranger cannot delete someone else's presence row", async () => {
  await assertFails(
    deleteDoc(doc(asUser(STRANGER).firestore(), ...DOC_PATH, "presence", EDITOR))
  );
});

// ---------------------------------------------------------------- directory binding

test("directory write is refused when the email is not the caller's own verified email", async () => {
  // The poisoning attack this closes: write an arbitrary address into the lookup table, then wait
  // for the owner to share a document with that address and receive the document instead.
  await assertFails(
    setDoc(doc(asUser(STRANGER).firestore(), "directory", "somehash"), {
      uid: STRANGER,
      email: "victim@example.com",
    })
  );
});

test("directory write succeeds for the caller's own email", async () => {
  await assertSucceeds(
    setDoc(doc(asUser(OWNER).firestore(), "directory", "somehash"), {
      uid: OWNER,
      email: `${OWNER}@example.com`,
    })
  );
});

test("directory write cannot claim another uid", async () => {
  await assertFails(
    setDoc(doc(asUser(STRANGER).firestore(), "directory", "somehash"), {
      uid: OWNER,
      email: `${STRANGER}@example.com`,
    })
  );
});