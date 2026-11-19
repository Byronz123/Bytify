// ==========================================
// Bytify/functions/api/subtitles/file.js
// ==========================================
//
// URL STABIL per berkas subtitle; dialihkan (302) ke tautan sementara
// OpenSubtitles saat diminta.
//
// Inilah yang disimpan sebagai `MovieSubtitleDto.url` di APK. Tautan sementara
// OpenSubtitles berumur pendek, jadi menyimpannya langsung akan basi; dan
// dengan pengalihan, kuota unduh baru terpakai ketika pemutar benar-benar
// mengambil berkasnya — bukan ketika daftar subtitle ditampilkan.
import { subtitleServiceDariEnv } from '../../../backend/api/src/subtitles.mjs';

export async function onRequestGet({ request, env }) {
    const fileId = new URL(request.url).searchParams.get('fileId');
    const layanan = subtitleServiceDariEnv(env);
    const hasil = await layanan.tautan({ fileId });
    if (hasil.status !== 200) {
        return Response.json({
            ok: false,
            error: { code: 'SUBTITLE_LINK_UNAVAILABLE', message: hasil.error },
        }, {
            status: hasil.status,
            headers: { 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' },
        });
    }
    // SubDL dikirim sebagai ISI (sudah dibongkar dari ZIP); OpenSubtitles tetap
    // sebagai pengalihan supaya kuotanya baru terpakai saat berkasnya diunduh.
    if (typeof hasil.body === 'string') {
        return new Response(hasil.body, {
            status: 200,
            headers: {
                'Content-Type': hasil.contentType || 'text/plain; charset=utf-8',
                'Access-Control-Allow-Origin': '*',
                'Cache-Control': 'no-store',
            },
        });
    }
    return new Response(null, {
        status: 302,
        headers: {
            Location: hasil.link,
            'Access-Control-Allow-Origin': '*',
            'Cache-Control': 'no-store',
        },
    });
}

export function onRequestOptions() {
    return new Response(null, {
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, OPTIONS',
        },
    });
}
