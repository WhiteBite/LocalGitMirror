#!/usr/bin/env node
/*
 * npm_request_check.js — сверяет npm-кеш РАБОЧЕЙ машины с ТОЧНЫМИ integrity
 * из текущего запроса frontend (взяты прямо из манифеста на сервере).
 *
 * В отличие от npm_cache_diag.js, тут integrity НЕ берутся из локального
 * yarn.lock (который может отличаться) — они зашиты ниже и совпадают с тем,
 * что реально ищет respond в плагине. Если тут 15/15 — respond обязан найти.
 *
 * Запуск (где угодно, проект не нужен):
 *     node npm_request_check.js
 */
const fs = require("fs");
const path = require("path");
const os = require("os");
const { execSync } = require("child_process");

// === Точные координаты из запроса frontend (manifest id 254b79ef) ===
const WANTED = [
  ["@krypto-sdk/beacon@0.12.4", "sha512-FsW9qeHa26oiB+V2gytAPjA+JXh9Y4RpEkk/CAF+ee6TV22yRoTgIKEr4hmZuUO2QJJdkoWUJYhPK7Em+89SZw=="],
  ["@krypto-sdk/carrier@1.2.9", "sha512-me/pA28zGJ0f0VJE6cxixYobXfJCr18CNHCfqnxQLeR56o16frw4wuiMOdaubIKyshYJKRY3gmQCDMHttZEjdA=="],
  ["@krypto-sdk/media@1.14.4", "sha512-5e+0JoMfZ3QSXa4WtGO4toCw9sjLNKu2MdVt1pGt4hzFZ1lzMYBXveUSTr6KS1hmo+oXbfGw+4C9TaWxIhYnRQ=="],
  ["@krypto-ui/auth@3.4.0", "sha512-DQNRycFs3q09Q+jhzGdRygHi9pWRiEy+GEZEAzWni7Q2W+JkXI948WxYtANJXSfw48IIwmEacMdrkFPxk6hXbw=="],
  ["@krypto-ui/charts@2.5.0", "sha512-GUtfwK7OQqTOOQ+ZmQ7k27EkV5gzBF91D8UvK5mG6ttyJ6MaYvhQSIjNkR30IyQG+c36UsVIL5ngLuWGXQLjjQ=="],
  ["@krypto-ui/components@2.23.2", "sha512-/HoEIcrlLPb+QYJxofLrDvCuMBqw83BBb9VVeBtkhgPcuQg9lbEBBnn8KSOQHy2OYTI9rbef5NyzfpwJcn3q/g=="],
  ["@krypto-ui/config@1.8.2", "sha512-jgZMtvOW8X7dN58GYob+NBB7ufGaBl40UsNYfhFCjDckCd5uEXuytqIBKaR1/1kyeL1SxOh6Gxs9cY/xYxlabA=="],
  ["@krypto-ui/core@0.30.0", "sha512-FsVfR8IyXZhA3qYVNdx25MK3Mv99ex8jHBa6aTm/JvZxDRbsIlbYhfOLFjkFDHVHlmjZbszQJgMyDQHg71sKvg=="],
  ["@krypto-ui/datepicker@3.4.0", "sha512-DamtAuEskXhMlCqEjQrpRJTpEWdd5jifQIk4Cl8D1yjkTrGcZ4f4clgwcM03DZiEsyihwaJo2vBSPF7cjW/Klw=="],
  ["@krypto-ui/icons@2.5.0", "sha512-8GIwg9IOEzRoU7hnrLkgPQ1LZh35n3BaDsG1rNo37ujgyU4xxiK2hxr0X47IwcUt8i5IL672oH3hly/bSFjJLA=="],
  ["@krypto-ui/map@3.27.0", "sha512-NybdYcylD+u88kDbSuysbdgq8HOSaCffjc9F8kZOIfYoqYcClug4VhavlXD4CpeqYM0+xxakBR94G7glTqTSGg=="],
  ["@krypto-ui/player@2.15.1", "sha512-PgkPLyeMQd2MsIoCPbFKCsjoYlaNh4Y+9oEk/ts1Ly25FDpbfYbg1zz8VE0DQcxAadYKi2gmYqOIjfjovlQv/w=="],
  ["@krypto-ui/table@2.6.1", "sha512-PiJaaevn7jbMO/ulg4KC5tgXBkzv7qqCZcZQMBcKi6vRIeL9tLkSbCwOdTc9fLRNrNp87JVFNEeCGssmixgqUA=="],
  ["@krypto-ui/tools@1.1.0", "sha512-1XgMimwqcOPEHPJO86viciJ1zDLpAvbjKhsFDoQBrZRC5H+Ag5ti6nqulO4c9m7jkkqeGO9TZqKGr/AYyjkQjA=="],
  ["krypto-floating@5.2.8", "sha512-vo9F2dOqkRTfYi/2IOGFmax6bk1lsBHh842Eq2XDET5UuJgZAED3/eH+kmccNz4Ik/I0qAWWIfWulTW+TAmBmA=="],
];

function cacachePath(cacheDir, integrity) {
  const dash = integrity.indexOf("-");
  const algo = integrity.slice(0, dash).toLowerCase();
  const hex = Buffer.from(integrity.slice(dash + 1), "base64").toString("hex");
  return path.join(cacheDir, "_cacache", "content-v2", algo,
    hex.slice(0, 2), hex.slice(2, 4), hex.slice(4));
}

let cacheDir = "";
try { cacheDir = execSync("npm config get cache", { encoding: "utf8" }).trim(); }
catch (e) { /* fall back below */ }
if (!cacheDir) {
  const lad = process.env.LOCALAPPDATA;
  cacheDir = lad ? path.join(lad, "npm-cache") : path.join(os.homedir(), "AppData/Local/npm-cache");
}

console.log("=== проверка кеша против ТОЧНОГО запроса frontend ===");
console.log("npm cache dir:", cacheDir);
console.log();

let present = 0;
const missing = [];
for (const [label, integrity] of WANTED) {
  const p = cacachePath(cacheDir, integrity);
  if (fs.existsSync(p)) { present++; console.log("  [ЕСТЬ] " + label); }
  else { missing.push(label); console.log("  [НЕТ ] " + label + "  -> " + p); }
}

console.log();
console.log(`итог: ${present}/${WANTED.length} найдено по точным integrity из запроса`);
if (present === WANTED.length) {
  console.log(">>> Кеш полностью совпадает с запросом. respond ОБЯЗАН их найти.");
  console.log(">>> Если плагин всё равно пишет 0 — проблема НЕ в кеше, а в repo:");
  console.log("    respond отвечает на запрос repo='frontend'. Проверь, что плагин");
  console.log("    в открытом проекте резолвит repo именно 'frontend' (а не onyx-platform).");
} else {
  console.log(">>> ВОТ ПРИЧИНА: этих пакетов нет по integrity из запроса.");
  console.log("    Значит твой npm i скачал ДРУГИЕ версии/тарболы, чем те, по которым");
  console.log("    собран запрос (yarn.lock на машинах различается: 1377 vs 1430).");
  console.log("    Решение: либо пересобрать запрос с домашней машины по ЕДИНОМУ yarn.lock,");
  console.log("    либо на работе докинуть точные версии: npm cache add <pkg>@<version>.");
}
