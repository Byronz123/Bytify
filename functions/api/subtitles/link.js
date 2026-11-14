// ==========================================
// Bytify/functions/api/subtitles/link.js
// ==========================================
//
// Tautan unduh sementara untuk satu berkas subtitle.
//
// MEMAKAN kuota unduh OpenSubtitles, jadi hanya dipanggil ketika pengguna
// benar-benar menyalakan subtitle — bukan saat membuka halaman detail. Karena
// itu jawabannya TIDAK disinggahi: tautannya berumur pendek.
import { subtitleServiceDariEnv } from '../../../backend/api/src/subtitles.mjs';

function jsonResponse(body, status = 200) {
    return Response.json(body, {
        status,
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Cache-Control': 'no-store',
        },
    });
}

export async function onRequestGet({ request, env }) {
    const q = new URL(request.url).searchParams;
    const layanan = subtitleServiceDariEnv(env);
    const { status, ...sisa } = await layanan.tautan({ fileId: q.get('fileId') });
    if (status !== 200) {
        return jsonResponse({
            ok: false,
            error: { code: 'SUBTITLE_LINK_UNAVAILABLE', message: sisa.error },
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
