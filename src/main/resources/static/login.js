import { sessionRequest } from './session.js';

const form = document.getElementById('login-form');
const fields = document.getElementById('login-fields');
const error = document.getElementById('login-error');
const button = document.getElementById('sign-in');
const notice = document.getElementById('login-notice');
const query = new URLSearchParams(window.location.search);
if (query.has('expired') || query.has('signed-out')) {
    notice.textContent = query.has('expired') ? 'Your session has ended. Sign in again to continue.' : 'You have signed out.';
    notice.hidden = false;
}

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (fields.disabled) return;
    const body = new URLSearchParams(new FormData(form));
    fields.disabled = true;
    button.textContent = 'Signing in…';
    form.setAttribute('aria-busy', 'true');
    error.hidden = true;
    try {
        const response = await sessionRequest('./api/auth/login', { method: 'POST', body });
        if (!response.ok) {
            throw new Error(response.status === 401 ? 'Username or password is incorrect.'
                : response.status === 403 ? 'Your sign-in form has expired. Please try again.'
                    : 'Could not sign in. Please try again.');
        }
        document.getElementById('password').value = '';
        window.location.replace('./');
    } catch (failure) {
        document.getElementById('password').value = '';
        error.textContent = failure.name === 'TypeError' || failure.name === 'AbortError'
            ? 'Sign-in could not be confirmed. Check your connection and try again.' : failure.message;
        error.hidden = false;
        error.focus();
    } finally {
        fields.disabled = false;
        button.textContent = 'Sign in';
        form.setAttribute('aria-busy', 'false');
    }
});
