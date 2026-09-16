import { element, request } from './workbench.js';

const error = element('account-error');
const signOut = element('sign-out');

async function loadAccount() {
    error.hidden = true;
    try {
        const account = await request('./api/auth/session');
        element('signed-in-user').textContent = account.username;
    } catch {
        error.textContent = 'Could not load your account. Refresh the page to try again.';
        error.hidden = false;
    }
}

signOut.addEventListener('click', async () => {
    if (signOut.disabled) return;
    signOut.disabled = true;
    error.hidden = true;
    try {
        await request('./api/auth/logout', { method: 'POST' });
        window.location.replace('./login.html?signed-out');
    } catch {
        error.textContent = 'Sign-out could not be confirmed. Please try again.';
        error.hidden = false;
    } finally {
        signOut.disabled = false;
    }
});

// Recheck the session when browser history restores a cached page.
window.addEventListener('pageshow', (event) => { if (event.persisted) window.location.reload(); });
loadAccount();
