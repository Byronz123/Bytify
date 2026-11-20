// ==========================================
// Bytify/functions/api/subtitles.js
// ==========================================
//
// Daftar subtitle untuk satu judul, lewat API resmi OpenSubtitles.
//
// Kunci API dibaca dari environment Cloudflare (Settings -> Environment
// variables, tandai sebagai Secret), BUKAN dari source dan BUKAN dari APK.
// Kunci yang ditanam di APK bisa diambil siapa pun yang membongkar berkasnya.
//
// Endpoint ini TIDAK memakan kuota unduh OpenSubtitles; hanya
// /api/subtitles/link yang memakannya.
import { subtitleServiceDariEnv } from '../../backend/api/src/subtitles.mjs';

function jsonResponse(body, status = 200) {
    return Response.json(body, {
        status,
        headers: {
            'Access-Control-Allow-Origin': '*',
            // Judul yang sama sering dibuka berulang, dan daftar subtitle
            // sebuah film praktis tidak berubah dari jam ke jam. Dengan 600
            // detik, sepuluh penonton film yang sama dalam sehari berarti
            // belasan permintaan ke upstream; dengan sehari, cukup satu.
            // Itu yang membuat kuota berkuota-tetap (SubDL 1.000/hari, Wyzie
            // 2 juta sekali beli) bertahan lama. Endpoint ini TIDAK memakan
            // kuota unduh OpenSubtitles, jadi yang dihemat kuota pencarian
            // kedua sumber lain.
            'Cache-Control': status === 200 ? 'public, max-age=86400' : 'no-store',
        },
    });
}

export async function onRequestGet({ request, env }) {
    const q = new URL(request.url).searchParams;
    const layanan = subtitleServiceDariEnv(env);
    const { status, ...sisa } = await layanan.cari({
        tmdbId: q.get('tmdbId'),
        languages: q.get('lang'),
        type: q.get('type'),
        season: q.get('season'),
        episode: q.get('episode'),
    });
    if (status !== 200) {
        return jsonResponse({
            ok: false,
            error: { code: 'SUBTITLE_UNAVAILABLE', message: sisa.error },
        }, status);
    }
    return jsonResponse({ ok: true, ...sisa });
}

export function onRequestOptions() {
    return new Response(null, {
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, OPTIONS',
        },
    });
}
