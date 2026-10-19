# Paper Docs — website

Static site for **Paper Docs**, hosting the homepage and privacy policy on Firebase Hosting.

| File | Purpose |
| --- | --- |
| `public/index.html` | Homepage |
| `public/privacy-policy.html` | Privacy policy (required by Google review) |
| `public/404.html` | Not-found page (Firebase requires one) |
| `public/ic_launcher.png` | App icon, copied from `app/src/main/assets` |
| `public/google921f13e98611484d.html` | Search Console HTML-tag site verification |

## Deploy

```bash
cd site
npx firebase-tools login          # one-time, opens a browser
npx firebase-tools deploy --only hosting
```

The site will be live at:

- `https://paper-docs-006.web.app`
- `https://paper-docs-006.firebaseapp.com`

Preview a local copy before deploying:

```bash
cd public && python3 -m http.server 8765
```

## Before you publish

Both items previously listed here are resolved. `privacy-policy.html` carries a real contact
address, and it describes the account-deletion and cloud-deletion behaviour the app actually
implements (`Delete my account and data` and `Delete my cloud data` in the account sheet).

## Analytics

The Firebase web config you supplied includes `measurementId` and calls `getAnalytics`. Those are
**not required** for this static site, and the site loads no JavaScript at all.

If you do add Analytics to the site, disclose it on the site itself. The privacy policy's
"no analytics" claim is scoped to the Android app and must stay that way, or be updated to match.

The web `apiKey` in that config is designed to be public — it is safe to ship in a browser bundle.
What actually protects your project is Firestore security rules and App Check. If the web app ever
reads Firestore directly, require App Check and keep the rules locked down.

## Contents

```
site/
├── firebase.json          Hosting config
├── .firebaserc            Links the folder to project paper-docs-006
└── public/                Files that are deployed
```