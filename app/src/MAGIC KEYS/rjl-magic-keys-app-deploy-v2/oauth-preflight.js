(() => {
  if (!window.supabase?.createClient || window.__rjlSupabaseWrapped) return;

  const originalCreateClient = window.supabase.createClient.bind(window.supabase);

  window.supabase.createClient = (url, key, options = {}) => {
    const authOptions = options.auth || {};
    const client = originalCreateClient(url, key, {
      ...options,
      auth: {
        ...authOptions,
        persistSession: true,
        autoRefreshToken: true,
        detectSessionInUrl: true,
        flowType: "pkce",
        storage: window.sessionStorage
      }
    });

    const originalSignOut = client.auth.signOut.bind(client.auth);
    client.auth.signOut = async (signOutOptions = {}) => {
      const shouldResumeGoogleSession = window.sessionStorage.getItem("rjl.oauth.resume") === "1";
      const isLocalSignOut = !signOutOptions?.scope || signOutOptions.scope === "local";

      if (shouldResumeGoogleSession && isLocalSignOut) {
        window.sessionStorage.removeItem("rjl.oauth.resume");
        return { error: null };
      }

      return originalSignOut(signOutOptions);
    };

    window.__rjlSupabaseClient = client;
    return client;
  };

  window.__rjlSupabaseWrapped = true;
})();
