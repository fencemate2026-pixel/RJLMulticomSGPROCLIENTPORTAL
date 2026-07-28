RJL Android Magic Key Connector

Run install-magic-key-connector.ps1 in PowerShell.

It:
- backs up the existing Magic Key files
- points the Android app at the live Supabase Edge Function
- updates request/response models
- restricts input to six digits
- builds and tests the Android app

Live endpoint:
https://ifesjmdhlyurswgajslm.supabase.co/functions/v1/verify-magic-key
