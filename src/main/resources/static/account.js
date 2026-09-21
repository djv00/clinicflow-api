import { element, request } from './workbench.js';

const error = element('account-error');
const signOut = element('sign-out');
let roles = [];

export const canWrite = () => roles.includes('OPERATOR');

async function loadAccount() {
    error.hidden = true;
    try {
        const account = await request('./api/auth/session');
        roles = account.roles || [];
        element('signed-in-user').textContent = account.username;
        element('account-role').textContent = canWrite() ? 'Operator' : roles.includes('VIEWER') ? 'Read-only' : 'No business access';
        element('read-only-notice').hidden = !roles.includes('VIEWER') || canWrite();
        for (const id of ['register-patient', 'admit-patient']) element(id).hidden = !canWrite();
    } catch {
        error.textContent = 'Could not load your account permissions. Editing is unavailable. Refresh the page to try again.';
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
export const accountReady = loadAccount();
