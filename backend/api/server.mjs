import { loadEnvFile, readConfig } from './src/config.mjs';
import { createLogger } from './src/util.mjs';
import { createLiveService } from './src/live.mjs';
import { createMovieService } from './src/movies.mjs';
import { createApp } from './src/app.mjs';

loadEnvFile();
const config = readConfig();
const log = createLogger(config.logLevel);

const movies = createMovieService(config.movies, log);
const live = createLiveService(config.live, log);
const app = createApp({ movies, live, log, config });
const server = app.server();

server.listen(config.port, config.host, () => {
  log.info('Bytify API berjalan', { port: config.port, movieProviders: config.movies.providers, liveEnabled: live.enabled });
  movies.start();
  live.start();
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
