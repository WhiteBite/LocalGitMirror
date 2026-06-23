#!/usr/bin/env node
/*
 * npm_cache_diag.js — диагностика для LocalGitMirror "respond" (npm).
 *
 * Запускать НА РАБОЧЕЙ машине, в папке frontend-проекта (там, где yarn.lock):
 *     node npm_cache_diag.js
 * либо указать путь к проекту:
 *     node npm_cache_diag.js D:\path\to\frontend
 *
 * Что делает:
 *   1. Узнаёт РЕАЛЬНЫЙ npm cache dir (`npm config get cache`) — источник истины.
 *   2. Показывает, куда смотрит плагин (%LocalAppData%\npm-cache / npm_config_cache).
 *   3. Парсит yarn.lock, берёт корпоративные пакеты (resolved не из public npm),
 *      и для каждого по его integrity вычисляет путь в _cacache/content-v2 —
 *      ровно так же, как это делает плагин — и проверяет, есть ли файл.
 *   4. Печатает, сколько найдено / не найдено и где именно.
 */
const fs = require("fs");
const path = require("path");
const os = require("os");
const { execSync } = require("child_process");

const PUBLIC_HOSTS = ["registry.npmjs.org", "registry.yarnpkg.com", "registry.npmmirror.com"];

function hostOf(url) {
  const noScheme = url.includes("://") ? url.split("://")[1] : url;
  return noScheme.split("/")[0].split("@").pop().split(":")[0].toLowerCase();
}
function isPublic(url) {
  const h = hostOf(url);
  return PUBLIC_HOSTS.some((p) => h === p || h.endsWith("." + p));
}

// cacache path: _cacache/content-v2/<algo>/<hh>/<hh>/<rest-of-hex>
function cacachePath(cacheDir, integrity) {
  if (!integrity || !integrity.includes("-")) return null;
  const dash = integrity.indexOf("-");
  const algo = integrity.slice(0, dash).toLowerCase();
  const b64 = integrity.slice(dash + 1);
  let hex;
  try {
    hex = Buffer.from(b64, "base64").toString("hex");
  } catch (e) {
    return null;
  }
  if (hex.length < 4) return null;
  return path.join(cacheDir, "_cacache", "content-v2", algo,
    hex.slice(0, 2), hex.slice(2, 4), hex.slice(4));
}

// Minimal yarn.lock (classic v1) parser -> [{name, version, integrity}]
function parseYarnLock(text) {
  const lines = text.split(/\r?\n/);
  const out = {};
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trim();
    const isHeader = line && line[0] !== " " && line[0] !== "\t" && line[0] !== "#" && trimmed.endsWith(":");
    if (!isHeader) { i++; continue; }
    const firstDesc = trimmed.replace(/:$/, "").split(",")[0].trim().replace(/^"|"$/g, "");
    const at = firstDesc.lastIndexOf("@");
    const name = at > 0 ? firstDesc.slice(0, at) : firstDesc;
    let version = "", resolved = "", integrity = "";
    let j = i + 1;
    while (j < lines.length) {
      const bl = lines[j];
      if (bl && bl[0] !== " " && bl[0] !== "\t") break;
      const bt = bl.trim();
      if (bt.startsWith("version ")) version = bt.slice(8).trim().replace(/^"|"$/g, "");
      else if (bt.startsWith("resolved ")) resolved = bt.slice(9).trim().replace(/^"|"$/g, "").split("#")[0];
      else if (bt.startsWith("integrity ")) integrity = bt.slice(10).trim();
      j++;
    }
    if (resolved && resolved.startsWith("http") && !isPublic(resolved) && name && version) {
      out[name + "@" + version] = { name, version, integrity };
    }
    i = j;
  }
  return Object.values(out);
}

function main() {
  const projectDir = process.argv[2] || process.cwd();
  console.log("=== npm cache diagnostics ===");
  console.log("project dir :", projectDir);

  // 1. real cache dir
  let realCache = "";
  try {
    realCache = execSync("npm config get cache", { encoding: "utf8" }).trim();
  } catch (e) {
    console.log("  ! 'npm config get cache' failed:", e.message);
  }
  // 2. where the plugin looks
  const envCache = process.env.npm_config_cache || "";
  const localAppData = process.env.LOCALAPPDATA || "";
  const pluginGuess = envCache
    ? envCache
    : (localAppData ? path.join(localAppData, "npm-cache") : path.join(os.homedir(), "AppData/Local/npm-cache"));

  console.log("npm config cache (ИСТИНА)   :", realCache || "(?)");
  console.log("npm_config_cache (env)      :", envCache || "(не задана)");
  console.log("плагин будет смотреть в      :", pluginGuess);
  const mismatch = realCache && path.resolve(realCache) !== path.resolve(pluginGuess);
  console.log(mismatch
    ? "  >>> ВНИМАНИЕ: путь плагина НЕ совпадает с реальным кешем — вот и причина 0 найдено."
    : "  путь плагина совпадает с реальным кешем (ОК).");

  // candidate cache dirs to actually probe
  const cacheDirs = [];
  if (realCache) cacheDirs.push(["real", realCache]);
  if (path.resolve(realCache || "") !== path.resolve(pluginGuess)) cacheDirs.push(["plugin", pluginGuess]);

  // 3. parse yarn.lock
  const lockPath = path.join(projectDir, "yarn.lock");
  if (!fs.existsSync(lockPath)) {
    console.log("\n! yarn.lock не найден в", projectDir);
    console.log("  Укажи путь к проекту: node npm_cache_diag.js <путь к frontend>");
    return;
  }
  const corp = parseYarnLock(fs.readFileSync(lockPath, "utf8"));
  // Те, что реально приедут из nexus и нужны для respond — по имени (krypto*).
  // Можно переопределить: node npm_cache_diag.js <dir> "krypto,@othercorp"
  const filterCsv = process.argv[3] || "krypto";
  const needles = filterCsv.split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);
  const matchesNeedle = (name) => needles.some((n) => name.toLowerCase().includes(n));
  const targeted = corp.filter((c) => matchesNeedle(c.name));
  console.log(`\nвсего не-публичных записей в yarn.lock: ${corp.length}`);
  console.log(`из них КОРПОРАТИВНЫХ (по фильтру '${filterCsv}'): ${targeted.length}`);

  for (const [label, dir] of cacheDirs) {
    console.log(`\n--- проверка кеша [${label}]: ${dir} ---`);
    const contentV2 = path.join(dir, "_cacache", "content-v2");
    console.log("  _cacache/content-v2 существует:", fs.existsSync(contentV2));

    const checkSet = (list, title) => {
      let present = 0, absent = 0, noIntegrity = 0;
      const absentList = [];
      for (const c of list) {
        if (!c.integrity) { noIntegrity++; absentList.push(c.name + "@" + c.version + " (нет integrity)"); continue; }
        const p = cacachePath(dir, c.integrity);
        if (p && fs.existsSync(p)) present++;
        else { absent++; absentList.push(c.name + "@" + c.version); }
      }
      console.log(`  ${title}: найдено ${present}/${list.length} (нет: ${absent}, без integrity: ${noIntegrity})`);
      if (absentList.length) {
        absentList.slice(0, 40).forEach((x) => console.log("      -", x));
      }
      return { present, absent };
    };

    console.log("  >>> КОРПОРАТИВНЫЕ (это и нужно для respond):");
    const r = checkSet(targeted, "корпоративные");
    if (r.present === targeted.length && targeted.length > 0) {
      console.log("      OK — все корпоративные пакеты в кеше, respond должен их найти.");
    } else if (r.absent > 0) {
      console.log("      !!! части корпоративных пакетов НЕТ в кеше — respond их не отгрузит.");
      console.log("          Проверь: npm i реально качал из nexus? Версии совпали с yarn.lock?");
    }
    // Остальное — справочно (публичные/платформенные, для respond не нужны).
    const rest = corp.filter((c) => !matchesNeedle(c.name));
    let restPresent = 0;
    for (const c of rest) {
      const p = c.integrity ? cacachePath(dir, c.integrity) : null;
      if (p && fs.existsSync(p)) restPresent++;
    }
    console.log(`  (справочно) прочие не-публичные: ${restPresent}/${rest.length} в кеше — для respond не важны`);
  }

  console.log("\nЕсли в реальном кеше пакеты ЕСТЬ, а путь плагина не совпадает —");
  console.log("задай плагину тот же кеш через переменную окружения npm_config_cache,");
  console.log("либо запусти respond так, чтобы npm-кеш был в %LocalAppData%\\npm-cache.");
}

main();
