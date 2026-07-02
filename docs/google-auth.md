# Google social login

AtmosPath uses Cognito Hosted UI with authorization-code flow and PKCE. Email/password login works without additional configuration. Google login is enabled only when both OAuth values are supplied.

## Google Cloud configuration

1. Create an OAuth 2.0 Web application in Google Cloud Console.
2. Use the Cognito callback URL:

   `https://<cognito-domain>.auth.us-east-1.amazoncognito.com/oauth2/idpresponse`

3. Add these GitHub environment secrets to `dev`:

   - `GOOGLE_OAUTH_CLIENT_ID`
   - `GOOGLE_OAUTH_CLIENT_SECRET`

4. Run the `Deploy Dev` workflow.

Terraform creates the Cognito Google identity provider and adds `Google` to the web client only when both secrets are present. No application code change is required.

The web build reads Terraform output `google_auth_enabled` and exposes it as `VITE_GOOGLE_AUTH_ENABLED`.
When the value is false, the UI keeps the Google entry point visible but stops locally with a clear setup message instead of redirecting users into a Cognito Bad Request.

## Security notes

- Never commit the Google client secret.
- Terraform provider details are stored in encrypted, access-controlled remote state.
- Rotate the secret if GitHub environment or Terraform-state access changes.
- Keep the native Cognito provider enabled as an operational fallback.
