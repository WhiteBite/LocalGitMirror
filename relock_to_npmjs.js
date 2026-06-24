#!/usr/bin/env node
/*
 * relock_to_npmjs.js — переписывает `resolved` URL в package-lock.json
 * с корпоративного nexus на публичный npmjs.
 *
 * Зачем: lockfile генерится на рабочей машине (резолв через nexus), поэтому все
 * resolved-ссылки ведут на nexus.kryptodev.ru, недоступный из дома. nexus
 * npm-all — это прокси npmjs, путь тарбола совпадает, поэтому замену префикса
 * хватает. integrity не трогаем — он у тарбола один и тот же.
 *
 * После этого:
 *   - публичные пакеты качаются с npmjs (валидный URL + integrity),
 *   - 15 корпоративных (@krypto-*) дают 404 на npmjs, НО они уже в npm-кеше по
 *     integrity, поэтому `npm ci --prefer-offline` берёт их из кеша, URL не дёргая.
 *
 * Использование:
 *   node relock_to_npmjs.js <путь к package-lock.json>
 * (правит файл на месте, рядом кладёт .bak)
 */
const fs = require("fs");

const file = process.argv[2];
if (!file) {
  console.error("usage: node relock_to_npmjs.js <package-lock.json>");
  process.exit(1);
}

// База nexus npm-registry. Подстрой при необходимости.
const NEXUS_PREFIXES = [
  "https://nexus.kryptodev.ru/repository/npm-all/",
  "https://nexus.kryptodev.ru/repository/npm/",
  "http://nexus.kryptodev.ru/repository/npm-all/",
];
const NPMJS = "https://registry.npmjs.org/";

let text = fs.readFileSync(file, "utf8");
const before = text;
let count = 0;
for (const pfx of NEXUS_PREFIXES) {
  // считаем и заменяем
  const parts = text.split(pfx);
  count += parts.length - 1;
  text = parts.join(NPMJS);
}
// общий счёт оставшихся nexus-ссылок (на случай иного префикса)
const leftover = (text.match(/nexus\.kryptodev\.ru/g) || []).length;

if (text === before) {
  console.log("nexus-ссылок не найдено — возможно, префикс другой.");
  const sample = (before.match(/"resolved":\s*"[^"]+"/g) || []).slice(0, 3);
  console.log("примеры resolved:");
  sample.forEach((s) => console.log("  " + s));
  process.exit(0);
}

fs.writeFileSync(file + ".bak", before);
fs.writeFileSync(file, text);
console.log(`переписано resolved-ссылок: ${count}`);
console.log(`осталось упоминаний nexus.kryptodev.ru: ${leftover} (если >0 — скинь пример, добавлю префикс)`);
console.log(`бэкап: ${file}.bak`);
