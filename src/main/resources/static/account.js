import { element, request } from './workbench.js';

const error = element('account-error');
const signOut = element('sign-out');
let roles = [];

export const canWrite = () => roles.includes('OPERATOR');
export const canReadClinical = () => roles.some((role) => ['OPERATOR', 'VIEWER'].includes(role));
export const canManagePhysicians = () => roles.includes('ADMIN');
export const canReadPhysicians = () => canReadClinical() || canManagePhysicians();

async function loadAccount() {
    error.hidden = true;
    try {
        const account = await request('./api/auth/session');
        roles = account.roles || [];
        element('signed-in-user').textContent = account.username;
        element('account-role').textContent = canManagePhysicians() ? 'Directory administrator'
            : canWrite() ? 'Operator' : roles.includes('VIEWER') ? 'Read-only' : 'No business access';
        if (element('read-only-notice')) element('read-only-notice').hidden = !roles.includes('VIEWER') || canWrite();
        for (const id of ['register-patient', 'admit-patient']) {
            if (element(id)) element(id).hidden = !canWrite();
        }
        for (const id of ['patients-link', 'inpatients-link']) element(id).hidden = !canReadClinical();
        element('physicians-link').hidden = !canReadPhysicians();
        if (element('patients-view') && !canReadClinical() && canManagePhysicians()) {
            window.location.replace('./physicians.html');
        }
    } catch {
        error.textContent = 'Could not load your account permissions. The workspace is unavailable. Refresh the page to try again.';
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
