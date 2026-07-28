# RJL Magic Keys Client App

This is a production-ready static web app for the Magic Keys client portal.

## Included

- Supabase email/password login
- Supabase invitation and password setup handling
- Password reset handling
- Secure call to the `get-magic-key` Edge Function
- Responsive phone, tablet and desktop layout
- RJL Commercial branding and advertising
- Installable web-app manifest
- Vercel security headers and SPA rewrites

## Deploy to Vercel

### Dashboard upload

1. Open the Vercel project `rjl-gate-access`.
2. Replace the current placeholder project with these files, or import this folder as the project root.
3. Deploy.
4. Confirm `https://rjl-gate-access.vercel.app` shows the login page.

### Vercel CLI

Run from this folder:

```bash
npx vercel --prod
```

Link it to the existing `rjl-gate-access` project when prompted.

## Connect the custom domain

In Vercel:

1. Open `rjl-gate-access`.
2. Go to **Settings → Domains**.
3. Add `keys.rjlcommercial.com.au`.
4. Copy the DNS record Vercel provides into the DNS provider for `rjlcommercial.com.au`.
5. Wait until Vercel shows the domain as valid.

## Required Supabase URL configuration

In Supabase project **Magic Keys**:

1. Open **Authentication → URL Configuration**.
2. Set **Site URL** to:
   `https://keys.rjlcommercial.com.au`
3. Add these **Redirect URLs**:
   - `https://keys.rjlcommercial.com.au/**`
   - `https://rjl-gate-access.vercel.app/**`

Existing invitation emails may contain an old redirect. After configuring these URLs, resend invitations if the old links do not open this app.

## Security

The browser uses a Supabase publishable key, which is designed for public frontend use. The actual six-digit key remains protected behind authenticated Supabase Edge Function access.
