# Firebase and Google setup

Paper Docs ships with sign-in, Drive sync, and live collaboration already coded. They switch on
once a Firebase project is wired in, and the app stays fully usable offline before that.

Current project: **`paper-docs-006`**, Android package **`com.company.paperdocs`**.

---

## What you already have

`app/google-services.json` is present and the `com.google.gms.google-services` plugin is applied
automatically when that file exists. The app's `applicationId` matches the registered package, so
the build succeeds.

## What is still missing

Your `google-services.json` has an **empty `oauth_client` array**. Google sign-in cannot work
without an OAuth client, and Drive needs a Drive API scope. Add both in the Firebase console.

---

## Setup steps

### 1. Register the debug signing key

Google sign-in on Android verifies the app's signing certificate. For debug builds the SHA-1 of
your machine's debug keystore is what matters. This machine's key is:

```
SHA1: 66:4A:1F:4C:D7:75:89:64:68:EE:D5:8B:FD:CC:DC:5E:A8:A3:1C:81
```

If you build on another machine, run:

```bash
keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore \
  -storepass android -keypass android | grep SHA1
```

**The release key is a separate fingerprint and it is the one that matters for shipping.** A release
build signed with `~/.keys/paperdocs-release.jks` presents:

```
SHA1: 1A:1E:4E:F8:E7:E3:FC:AA:24:C5:E2:CF:CB:86:32:14:BC:FB:66:8D
SHA-256: 7D:23:9C:72:3B:F2:A5:BD:7C:4A:BA:CC:8A:A0:49:2A:47:31:98:87:2B:A5:9D:13:0D:F2:4C:EF:48:00:49:84
```

Register **both**. Without the release SHA-1, Google sign-in fails on every published build with
`ApiException: 125`, while debug builds keep working, which makes it easy to miss.

To re-read it after a backup restore:

```bash
keytool -list -v -alias paperdocs -keystore ~/.keys/paperdocs-release.jks \
  -storepass "$(grep '^storePassword=' keystore.properties | cut -d= -f2-)" \
  | grep -E "SHA1:|SHA256:|Valid from"
```

Note that keytool prints `SHA1:` and `SHA256:` with no hyphen, so grep for `SHA256:` rather than
`SHA-256:`. A grep for the hyphenated form matches nothing and silently prints an empty result.

### 2. In the Firebase console

1. Open **Project settings → Your apps → Android app `com.company.paperdocs`**.
2. Click **Add fingerprint**, paste the debug SHA-1 above, and save.
3. Add the release SHA-1 as a second fingerprint on the same app.
4. Do the same in **Google Cloud Console → APIs & Services → Credentials** for the Android OAuth
   client. Adding it in only one of the two consoles still fails sign-in.

### 3. Enable the services

Go to the console and turn on:

| Service | Why it is needed |
|---|---|
| **Authentication → Sign-in method → Google** | Google sign-in for accounts |
| **Firestore Database** | Document sync and collaborator presence |

**Pick "Firestore Native" mode, not "Datastore".** A database created in Datastore mode accepts
some writes but rejects realtime listeners with:

```
FAILED_PRECONDITION: Listen queries are not supported for this database.
```

Live collaboration depends on listeners, so Datastore mode will not work and the mode cannot be
changed after creation — delete it and create a replacement.

This project uses the database id `default` (see `firestore_database_id` in
`app/src/main/res/values/strings.xml`). Note that a database literally named `default` is not the
built-in `(default)` database, which is why the app must be pointed at it explicitly.

Paste these rules into **Firestore Database → Rules**. They keep every user inside their own
documents while letting shared documents work, and they add the email directory that sharing
depends on. Without them every write fails with `Missing or insufficient permissions`. The same rules are in
[`firestore.rules`](../firestore.rules) at the repository root, ready to paste.

Sharing notes:

- `sharedWith` is a map of collaborator uid to role (`VIEWER` or `EDITOR`).
- Rules do **not** cascade to subcollections, so `sharedWith/{personUid}` and `presence/{personUid}`
  each need their own `match`.
- A `collectionGroup()` query is only authorized by a rule written with a **recursive wildcard**,
  `match /{path=**}/sharedWith/{personUid}`. The nested matches alone leave the shared-with-me
  lookup failing with `Missing or insufficient permissions`.
- A collection group query is authorized against the documents it returns and **never against
  their ancestors**, so a bare `allow read: if signedIn()` in a recursive wildcard leaks. With
  that rule, any signed-in user could run `collectionGroup("sharedWith").get()` and read every
  share entry in the app: owner uid, document id, document title, and role. The recursive rules
  here instead check the returned document's own `uid`, or that the caller owns the containing
  path.
- A viewer can read but cannot write; only the owner or an editor may change the text. An **editor
  may not touch `sharedWith`**: the update rule restricts editors to the content fields
  (`localId`, `title`, `content`, `marks`, `updatedAt`, `origin`) via `affectedKeys().hasOnly(...)`.
  Without that, an editor could grant themselves permanent EDITOR access or revoke other people.
  If you add a field to `CloudSync.payload()`, add it to that list or editor writes will fail.
- Presence rows are readable only by people who can open the document — the owner and anyone in
  its `sharedWith` map.
- The directory is readable by any signed-in user, so collaborators can be found by email.
  That means a signed-in user could enumerate who uses the app. A Cloud Function that resolves
  email to uid server side removes this exposure.
- The directory is writable **only for the caller's own verified address**: the write rule
  requires `request.resource.data.email == request.auth.token.email`. Without that binding, any
  signed-in user could register someone else's address in the lookup, and a later share to that
  address would resolve to the wrong account.

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    function signedIn() {
      return request.auth != null;
    }

    // Every subcollection the app uses sits at
    //   /users/{owner}/documents/{docId}/<name>/{personUid}
    // so the segments before the collection name are always
    //   path == ["users", owner, "documents", docId]
    // A recursive wildcard captures exactly those four segments, which lets the helpers below
    // recover the owner and document id without trusting anything the caller wrote.
    function docShape(path) {
      return path.size() == 4
             && path[0] == 'users'
             && path[2] == 'documents';
    }

    function ownsDocument(path) {
      return signedIn() && docShape(path) && path[1] == request.auth.uid;
    }

    // The owner always qualifies. Anyone else has to be named in the containing document's
    // sharedWith map, which is the same condition the document read rule uses.
    function canSeeDocument(path) {
      return ownsDocument(path)
             || (docShape(path)
                 && get(/databases/$(database)/documents/users/$(path[1])/documents/$(path[3]))
                      .data.sharedWith[request.auth.uid] != null);
    }

    // ---------------------------------------------------------------- directory

    // Email -> uid lookup, used when sharing. Readable by any signed-in user.
    //
    // Writes are bound to the caller's own verified email claim. Without that binding any signed-in
    // user could claim an arbitrary address in the table, and a later share to that address would
    // resolve to the wrong account: write SHA256("victim@example.com") -> attacker uid, wait for
    // the victim to share a document with their own address, and the document is handed over. The
    // email field must therefore equal the email on the caller's own ID token.
    match /directory/{emailHash} {
      allow read: if signedIn();
      allow create, update: if signedIn()
                            && request.resource.data.uid == request.auth.uid
                            && request.resource.data.email == request.auth.token.email;
      allow delete: if signedIn() && resource.data.uid == request.auth.uid;
    }

    // ---------------------------------------------------------------- documents

    match /users/{userId}/documents/{docId} {
      // Owners, plus anyone the document is shared with.
      allow get, list: if signedIn() && (
        request.auth.uid == userId ||
        resource.data.sharedWith[request.auth.uid] != null
      );
      allow create, delete: if signedIn() && request.auth.uid == userId;

      // Owners may change anything. Editors may change the text but not the access control list:
      // without the affectedKeys() restriction an editor could rewrite sharedWith and grant
      // themselves EDITOR permanently, or revoke the owner and the other collaborators. Only the
      // fields CloudSync.payload() actually writes as document content are allowed here.
      allow update: if signedIn() && (
        request.auth.uid == userId ||
        (resource.data.sharedWith[request.auth.uid] == 'EDITOR' &&
         request.resource.data.diff(resource.data).affectedKeys().hasOnly([
           'localId',
           'title',
           'content',
           'marks',
           'updatedAt',
           'origin'
         ]))
      );

      // Rules do not cascade to subcollections, so these need their own match.
      //
      // These are NOT left at `allow read: if signedIn()`. Firestore authorizes a document read by
      // the rules on that document alone, not on its parents, so an open read here would let any
      // signed-in user who knows (or guesses) an owner uid and a document id read the share list
      // and the presence list of a document they cannot otherwise open. The share entries carry
      // the document title, and the presence entries carry names and photo URLs.
      match /sharedWith/{personUid} {
        allow read: if signedIn() && (
          request.auth.uid == userId ||
          resource.data.uid == request.auth.uid
        );
        allow write: if signedIn() && request.auth.uid == userId;
      }

      match /presence/{personUid} {
        allow read: if signedIn() && (
          request.auth.uid == userId ||
          resource.data.uid == request.auth.uid ||
          get(/databases/$(database)/documents/users/$(userId)/documents/$(docId))
               .data.sharedWith[request.auth.uid] != null
        );
        allow write: if signedIn() && request.auth.uid == personUid;
      }
    }

    // ---------------------------------------------------------------- collection group rules

    // Firestore only authorizes a collectionGroup() query through a rule written with a recursive
    // wildcard, so the nested matches above do NOT cover the queries in
    // CloudSync.sharedWithMe() and CloudSync.deleteAllCloudData(). Without these the shared-with-me
    // lookup fails with PERMISSION_DENIED and nothing shared is ever listed.
    //
    // A bare `allow read: if signedIn()` here is a data leak, because a collection group query is
    // authorized against the documents it returns and never against their ancestors. Any signed-in
    // user could run collectionGroup("sharedWith").get() and read every share entry in the
    // application -- owner uid, document id, document title, role -- not just their own. So these
    // rules check the contents of the returned document or the owner of the containing path.

    // Shared-document index: the recipient may read their own entry (that is what
    // sharedWithMe() lists), and the owner may read the entries under their own document.
    match /{path=**}/sharedWith/{personUid} {
      allow get, list: if signedIn() && (
        resource.data.uid == request.auth.uid ||
        ownsDocument(path)
      );
      // hideShared() removes the signed-in user's own entry from someone else's document. The
      // nested rule above only lets the owner write here, so without this a recipient cannot hide
      // a document that was shared with them.
      allow delete: if signedIn() && personUid == request.auth.uid;
    }

    // Presence: only people who can open the document may see who else is in it. The policy states
    // this, and a bare `allow read: if signedIn()` would have exposed every user's viewing state
    // app-wide.
    match /{path=**}/presence/{personUid} {
      allow get, list: if signedIn() && canSeeDocument(path);
      allow create, update: if signedIn() && request.auth.uid == personUid;
      // The owner clears presence rows when a document is deleted; the nested rule above only
      // permits a user to write their own row, which would strand everyone else's rows behind.
      allow delete: if signedIn() && (request.auth.uid == personUid || ownsDocument(path));
    }
  }
}
```

### 4. Create the OAuth clients

In **Project settings → Your apps → your Android app**, scroll to **Google services → OAuth client**:

- The **Web** client already listed is your `default_web_client_id`. Copy it into
  `app/src/main/res/values/strings.xml`:

  ```xml
  <string name="default_web_client_id" translatable="false">YOUR-WEB-CLIENT-ID.apps.googleusercontent.com</string>
  ```

Drive uses the same OAuth client, so no second client is needed for Drive access.

### 5. Publish the OAuth consent screen

While the consent screen is still in testing, only accounts listed as test users can complete it.
An unlisted account is refused with:

```
Access blocked: <app> has not completed the Google verification process.  (Error 403: access_denied)
```

**[OAuth consent screen](https://console.cloud.google.com/apis/credentials/consent?project=paper-docs-006)**
→ Test users → **Add users** → your Google address.

To lift the restriction for everyone, switch the consent screen to **In production** and fill
in the app details, which requires Google's verification review.

### 6. Enable the Google Drive API

Drive sync uses the Drive REST v3 API directly, so the API must be switched on:

1. **Google Cloud Console → APIs & Services → Library**
2. Search **Google Drive API** and enable it.
3. The same project scopes are used for auth, so no extra credential is needed beyond the OAuth
   client above.

---

### Note on the credentials library

`googleid:1.1.1` is compiled against `androidx.credentials:1.3.0-beta01` while the app resolves
`1.5.0`. Newer Play services therefore hands back a generic `CustomCredential` instead of a
`GoogleIdTokenCredential`. `AccountManager` rebuilds the typed credential through its public
`createFrom(Bundle)` factory, so the mismatch is handled without pinning an older library.

## Verify it works

1. Build and install:

   ```bash
   JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug
   adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Open the app, tap the menu (top left), then **Sign in or sync**.
3. Tap **Continue with Google**. A successful sign-in shows your account card and a sync summary.
4. Open a document. Cards show a cloud icon once uploaded; the editor footer changes from
   "saved on your device" to "Synced with your account".
5. Sign in on a second device with the same account to see presence avatars and live edits.

---

## If something does not work

| Symptom | Cause | Fix |
|---|---|---|
| "Add google-services.json to enable cloud features" | File missing or plugin not applied | Confirm the file is at `app/google-services.json` |
| "Google sign-in needs a client id" | Empty `default_web_client_id` | Complete step 4 |
| Error 125 / `ApiException` | SHA-1 not registered | Complete step 1 |
| Sign-in works, Drive upload fails | Drive API not enabled | Complete step 5 |
| "Firebase rejected this app's credentials" (error 28444) | SHA-1 not registered, so no Android OAuth client exists | Complete step 1, then re-download `google-services.json` |
| Account chooser never opens | No Google account on the device | Add one in Settings → Accounts |
| "unexpected credential type" in logs | Play services returns `CustomCredential`, not the typed class | Fixed in `AccountManager` via `GoogleIdTokenCredential.createFrom`; do not downgrade `androidx.credentials` |
| "Permission denied" from Firestore | Security rules | Review the rules above |
| Drive returns 401 CREDENTIALS_MISSING | A Firebase ID token was sent instead of an OAuth access token | Fixed: Drive now requests its own consent grant |
| Drive consent shows "Access blocked / access_denied" | Account is not an OAuth test user | Add it under the consent screen's Test users |

---

## How each feature maps to the code

| Feature | Code |
|---|---|
| Sign-in, accounts, tokens | `app/src/main/java/com/company/paperdocs/cloud/AccountManager.kt` |
| Realtime sync, presence, sharing | `app/src/main/java/com/company/paperdocs/cloud/CloudSync.kt` |
| Drive list / upload / download / trash | `app/src/main/java/com/company/paperdocs/cloud/DriveSync.kt` |
| Account UI | `app/src/main/java/com/company/paperdocs/ui/AccountSheet.kt` |
| Sync state and conflict rules | `app/src/main/java/com/company/paperdocs/data/DocumentStore.kt` |
| Conflict tests | `app/src/androidTest/java/com/company/paperdocs/DocumentIntegrationTest.kt` |

### Data layout

```
users/{uid}/documents/{documentId}     title, content, marks, updatedAt, localId, origin
users/{uid}/documents/{documentId}/presence/{uid}   uid, name, photoUrl, viewingAt
```

The local SQLite database stays the source of truth on the device. Each document keeps a
`remote_id` and a `sync_state` (`LOCAL`, `SYNCING`, `SYNCED`, `CONFLICT`) so the library can show
honest status without a network round trip.

### Conflict handling

- Remote newer than local → the remote copy is adopted.
- Local edited after the last sync, then a remote update arrives → the document is marked
  `CONFLICT`, the local version is snapshotted into version history, and you choose which side to
  keep. Nothing is silently overwritten.