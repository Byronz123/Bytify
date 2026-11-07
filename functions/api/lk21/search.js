function jsonResponse(body, status = 200) {
    return Response.json(body, {
        status,
        headers: { 'Access-Control-Allow-Origin': '*' },
    });
}

export async function onRequestGet({ request, env }) {
    const query = new URL(request.url).searchParams.get('q')?.trim().toLowerCase() ?? '';
    if (query.length < 2) return jsonResponse({ movies: [] });

    const assetUrl = new URL('/api/catalog.json', request.url);
    const response = await env.ASSETS.fetch(assetUrl);
    if (!response.ok) {
        return jsonResponse({
            ok: false,
            error: { code: 'CATALOG_UNAVAILABLE', message: 'Katalog film belum tersedia.' },
        }, 503);
    }

    const catalog = await response.json();
    const movies = catalog.movies.filter((movie) => movie.title.toLowerCase().includes(query));
    return jsonResponse({ movies });
}

export function onRequestOptions() {
    return new Response(null, {
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, OPTIONS',
        },
    });
}
