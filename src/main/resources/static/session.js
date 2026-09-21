export async function sessionRequest(url, options = {}, controller = new AbortController()) {
    const timeout = setTimeout(() => controller.abort(), 15000);
    const settings = { signal: controller.signal, credentials: 'same-origin', cache: 'no-store' };
    try {
        const headers = new Headers(options.headers);
        headers.set('Accept', 'application/json');
        if (!['GET', 'HEAD', 'OPTIONS'].includes((options.method || 'GET').toUpperCase())) {
            // Authentication and logout rotate the token, including in another browser tab.
            const response = await fetch('./api/auth/csrf', settings);
            if (!response.ok) throw new Error('Could not prepare a secure request. Try again.');
            const csrf = await response.json();
            headers.set(csrf.headerName, csrf.token);
        }
        const response = await fetch(url, { ...options, ...settings, headers });
        const body = response.status === 204 ? null : await response.json().catch(() => null);
        return { status: response.status, ok: response.ok, body };
    } finally {
        clearTimeout(timeout);
    }
}

export function requireSignIn() {
    window.location.replace('./login.html?expired');
}
