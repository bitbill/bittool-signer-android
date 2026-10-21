import { build } from 'esbuild';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const result=await build({entryPoints:['src/index.js'],bundle:true,format:'iife',globalName:'BitToolSignerEngine',platform:'browser',target:['safari17'],minify:true,legalComments:'eof',outfile:'../core/engine/src/main/assets/engine/signer.js',metafile:true,define:{'process.env.NODE_ENV':'"production"','process.env.NODE_DEBUG':'""'},banner:{js:'// BitTool Signer 1.0.0 — generated offline engine. Rebuild: cd engine && npm ci && npm run build.\n'}});
const output=await readFile('../core/engine/src/main/assets/engine/signer.js');
await writeFile('bundle-manifest.json',JSON.stringify({version:'1.0.0',file:'core/engine/src/main/assets/engine/signer.js',sha256:createHash('sha256').update(output).digest('hex'),bytes:output.length,inputs:Object.keys(result.metafile.inputs).sort()},null,2)+'\n');
console.log(`Bundled ${output.length} bytes, SHA-256 ${createHash('sha256').update(output).digest('hex')}`);
