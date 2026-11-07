import express from 'express';
import cors from 'cors';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;
const DB_PATH = path.resolve(__dirname, '../data/bytify_database.json');

app.use(cors());
app.use(express.json());

function readDatabase() {
    if (!fs.existsSync(DB_PATH)) {
        return [];
    }
    const raw = fs.readFileSync(DB_PATH, 'utf-8');
    return JSON.parse(raw || '[]');
}

// Root Endpoint (Health Check)
app.get('/', (req, res) => {
    res.json({
        ok: true,
        service: "Bytify Backend API",
        endpoints: [
            "/api/movies",
            "/api/movies/search?q=judul",
            "/api/movies/:id"
        ]
    });
});

// Get All Movies
app.get('/api/movies', (req, res) => {
    try {
        const movies = readDatabase();
        res.json({
            ok: true,
            total: movies.length,
            data: movies
        });
    } catch (err) {
        res.status(500).json({ ok: false, error: { code: "SERVER_ERROR", message: err.message } });
    }
});

// Search Movies
app.get('/api/movies/search', (req, res) => {
    try {
        const query = (req.query.q || '').toLowerCase();
        const movies = readDatabase();
        const filtered = movies.filter(m => m.title.toLowerCase().includes(query));
        res.json({
            ok: true,
            total: filtered.length,
            data: filtered
        });
    } catch (err) {
        res.status(500).json({ ok: false, error: { code: "SERVER_ERROR", message: err.message } });
    }
});

// Get Movie Detail by ID
app.get('/api/movies/:id', (req, res) => {
    try {
        const movies = readDatabase();
        const movie = movies.find(m => m.id === req.params.id);
        if (!movie) {
            return res.status(404).json({ ok: false, error: { code: "NOT_FOUND", message: "Film tidak ditemukan." } });
        }
        res.json({ ok: true, data: movie });
    } catch (err) {
        res.status(500).json({ ok: false, error: { code: "SERVER_ERROR", message: err.message } });
    }
});

// Fallback 404
app.use((req, res) => {
    res.status(404).json({
        ok: false,
        error: { code: "NOT_FOUND", message: "Endpoint tidak ditemukan." }
    });
});

app.listen(PORT, '0.0.0.0', () => {
    console.log(`[Bytify API] Server berjalan di http://localhost:${PORT}`);
});