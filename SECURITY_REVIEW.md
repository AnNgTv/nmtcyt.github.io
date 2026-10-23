# Nightmare TCYT frontend review (2026-10-23)

## Changes in this review
- Guard the back-to-top listener when a page does not include `#back-to-top`.
- Guard page-specific profile elements so a successful login check on pages such as `topup.html` or `top-nap.html` does not throw a null-reference error.
- Escape API-provided usernames before inserting them into HTML in the recent-donors and leaderboard views.
- Format amounts defensively and validate response status/shape for selected endpoints.
- Trim card recharge form inputs without changing the API field names.
- Replace the misleading generic bank-transfer QR/instructions on `index.html` and `remote_index.html` with a link to the logged-in, user-specific QR on `topup.html`.

## Important limitations / follow-up required
- The frontend still stores the bearer token in `localStorage`. This is vulnerable to token theft if any XSS remains. Migrating to `HttpOnly`, `Secure`, `SameSite` cookies requires coordinated backend changes and cannot safely be completed in this static frontend alone.
- The bank transfer reference `NAP######` is generated in the browser from a username hash. Hash collisions are possible, and this repository does not include the backend bank-transfer reconciliation code. Confirm that the backend expects exactly the same format and independently validates the transfer before crediting a balance.
- The `Recharge-Backend` and `DatabaseMySQL` plugin sources are not present in this ZIP, so password hashing, authorization, payment callback signature validation, replay protection/idempotency, SQL safety, and in-game command execution could not be audited.
- Client-side checks are not a security boundary. The backend must validate usernames, card submissions, payment events, and authorization.
- No changes were made to the API routes or JSON field names.

## Validation
- `node --check` passed for `assets/js/main.js` and the inline scripts in `index.html`, `remote_index.html`, `top-nap.html`, and `topup.html`.
