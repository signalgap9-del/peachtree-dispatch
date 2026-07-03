# Google social login

AtmosPath uses Cognito Hosted UI with authorization-code flow and PKCE. Email/password login works without additional configuration. Google login is enabled only when both OAuth values are supplied.

## Google Cloud configuration

1. Create an OAuth 2.0 Web application in Google Cloud Console.
2. Use the Cognito callback URL:

   `https://<cognito-domain>.auth.us-east-1.amazoncognito.com/oauth2/idpresponse`

3. Keep the application callback URLs on the Cognito app client in sync with the origins used by the browser:

   - `https://<cloudfront-distribution-domain>/`
   - `http://localhost:5173/`
   - `http://127.0.0.1:5173/`

   Terraform now wires the deployed CloudFront URL plus the local dev URLs for the `dev` environment. A Cognito `Bad Request` during sign-in usually means one of these callback URLs is missing or the Google OAuth secret pair is not configured.

4. Add these GitHub environment secrets to `dev`:

   - `GOOGLE_OAUTH_CLIENT_ID`
   - `GOOGLE_OAUTH_CLIENT_SECRET`

5. Run the `Deploy Dev` workflow.

Terraform creates the Cognito Google identity provider and adds `Google` to the web client only when both secrets are present. No application code change is required.

The web build reads Terraform output `google_auth_enabled` and exposes it as `VITE_GOOGLE_AUTH_ENABLED`.
When the value is false, the UI keeps the Google entry point visible but stops locally with a clear setup message instead of redirecting users into a Cognito Bad Request.

## Smoke test

1. Open the deployed CloudFront URL.
2. Click `Continue with Google`.
3. Confirm the browser goes to Google account selection, then returns to `/`.
4. Confirm the header avatar changes from `IN` to the signed-in user's initials.
5. Open `/saved`, save a place or route, refresh, and confirm the saved item remains visible.

## Security notes

- Never commit the Google client secret.
- Terraform provider details are stored in encrypted, access-controlled remote state.
- Rotate the secret if GitHub environment or Terraform-state access changes.
- Keep the native Cognito provider enabled as an operational fallback.
