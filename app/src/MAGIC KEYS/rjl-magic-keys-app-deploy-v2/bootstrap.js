(() => {
  const appScript = document.createElement('script');
  appScript.src = '/app.js?v=20260728-5';
  appScript.defer = true;
  appScript.onerror = () => {
    document.getElementById('loadingState')?.classList.add('hidden');
    const errorState = document.getElementById('errorState');
    const errorText = document.getElementById('errorText');
    if (errorText) errorText.textContent = 'The secure portal failed to start. Refresh the page and try again.';
    errorState?.classList.remove('hidden');
  };
  document.body.appendChild(appScript);
})();
