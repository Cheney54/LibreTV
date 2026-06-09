import fs from 'fs';
import path from 'path';

const root = process.cwd();
const outDir = path.join(root, 'edgeone-static');

const files = [
  'index.html',
  'live.html',
  'player.html',
  'watch.html',
  'about.html',
  'admin-logs.html',
  'manifest.json',
  'robots.txt',
  'VERSION.txt',
  'service-worker.js'
];

const dirs = [
  'css',
  'js',
  'libs',
  'image',
  'data'
];

function copyFile(relativePath) {
  const source = path.join(root, relativePath);
  const target = path.join(outDir, relativePath);

  if (!fs.existsSync(source)) {
    return;
  }

  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.copyFileSync(source, target);
}

function copyDir(relativePath) {
  const source = path.join(root, relativePath);
  const target = path.join(outDir, relativePath);

  if (!fs.existsSync(source)) {
    return;
  }

  fs.cpSync(source, target, { recursive: true });
}

fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });

for (const file of files) {
  copyFile(file);
}

for (const dir of dirs) {
  copyDir(dir);
}

console.log(`EdgeOne static site generated at ${outDir}`);
