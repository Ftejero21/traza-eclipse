// Exporta los prompts de sistema de nexo-vscode (src/ai/prompts.ts) a resources/ai/prompts.json y copia
// nexo-ia.schemas.json. Se ejecuta a mano (no forma parte del build de Maven).
// Uso: node tools/export-prompts.mjs ../nexo-vscode
import { createRequire } from 'node:module';
import { copyFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const nexo = resolve(process.argv[2] ?? '../nexo-vscode');
const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, '..', 'bundles', 'com.ftejero21.traza', 'resources', 'ai');
const require = createRequire(join(nexo, 'package.json'));
const esbuild = require(join(nexo, 'node_modules', 'esbuild'));

const entry = `export * from './src/ai/prompts.ts';\nexport * from './src/ai/schema-validator.ts';\n`;
const built = esbuild.buildSync({
  stdin: { contents: entry, resolveDir: nexo, sourcefile: 'entry.ts', loader: 'ts' },
  bundle: true, write: false, format: 'esm', platform: 'node', target: 'node18', logLevel: 'error',
});
const code = built.outputFiles[0].text;
const mod = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);

function port(value) {
  if (typeof value === 'string') return value.replaceAll('extensión Traza para VS Code', 'plugin Traza para Eclipse').replaceAll('VS Code', 'Eclipse');
  if (Array.isArray(value)) return value.map(port);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, port(v)]));
  return value;
}

const system = {
  'FLOW': mod.systemFor('FLOW', 'es', false),
  'FLOW|agent': mod.systemFor('FLOW', 'es', true),
  'SIMULATE': mod.systemFor('SIMULATE', 'es', false),
  'SIMULATE_BEFORE': mod.systemFor('SIMULATE_BEFORE', 'es', false),
  'SIMULATE_AFTER': mod.systemFor('SIMULATE_AFTER', 'es', false),
  'LOGS': mod.systemFor('LOGS', 'es', false),
  'PROPOSE': mod.systemFor('PROPOSE', 'es', false),
  'PROPOSE|agent': mod.systemFor('PROPOSE', 'es', true),
  'UI_PLAN': mod.systemFor('UI_PLAN', 'es', false),
  'UI_MOCK': mod.systemFor('UI_MOCK', 'es', false),
  'UI_MOCK|agent': mod.systemFor('UI_MOCK', 'es', true),
  'DB_MAP': mod.systemFor('DB_MAP', 'es', false),
  'DB_MAP_SQL': mod.systemFor('DB_MAP_SQL', 'es', false),
  'DB_MAP_SQL|agent': mod.systemFor('DB_MAP_SQL', 'es', true),
  'DB_EXPLAIN|es': mod.systemFor('DB_EXPLAIN', 'es', false),
  'DB_EXPLAIN|en': mod.systemFor('DB_EXPLAIN', 'en', false),
  'FILE_SELECT': mod.systemFor('FILE_SELECT', 'es', false),
};

const answer = {
  FLOW_ANSWER_NOTE: mod.FLOW_ANSWER_NOTE,
  CHANGE_ANSWER_NOTE: mod.CHANGE_ANSWER_NOTE,
  ANSWER_SYSTEM: mod.ANSWER_SYSTEM,
  ANSWER_CONTINUE_NOTE: mod.ANSWER_CONTINUE_NOTE,
  ANSWER_BUDGET_NOTE: mod.ANSWER_BUDGET_NOTE,
  ANSWER_EMPTY_NOTE: mod.ANSWER_EMPTY_NOTE,
};
for (const kind of ['FLOW', 'PROPOSE']) {
  for (const lang of ['es', 'en']) {
    answer[`answerNote|${kind}|${lang}`] = mod.answerNote(kind, '{{QUESTION}}', lang, false);
    answer[`answerNote|${kind}|${lang}|tools`] = mod.answerNote(kind, '{{QUESTION}}', lang, true);
  }
}

const compactSchema = {};
for (const definition of ['FlowDiagram', 'SimulationResult', 'LogAnalysisResult', 'ChangeProposal', 'UiPreviewPlan', 'UiMock', 'FlowDbMap']) {
  compactSchema[definition] = mod.compactSchema(definition);
}

mkdirSync(out, { recursive: true });
writeFileSync(join(out, 'prompts.json'), `${JSON.stringify(port({ system, answer, compactSchema }), null, 1)}\n`, 'utf8');
copyFileSync(join(nexo, 'src', 'ai', 'nexo-ia.schemas.json'), join(out, 'nexo-ia.schemas.json'));
console.log(`Prompts exportados en ${out}`);
