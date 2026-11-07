export async function onRequestGet({ request, env }) {
    const assetUrl = new URL('/api/live/channels.json', request.url);
    const response = await env.ASSETS.fetch(assetUrl);
    const headers = new Headers(response.headers);
    headers.set('Access-Control-Allow-Origin', '*');
    headers.set('Cache-Control', 'public, max-age=300');
    return new Response(response.body, { status: response.status, headers });
}

export function onRequestOptions() {
    return new Response(null, {
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, OPTIONS',
        },
    });
}
