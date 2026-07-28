"use strict";
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");

const PROJECT_ID = "iiii-7b9e8";
const DATABASE_ID = "gsmsimcared";
const CAMPAIGN_ID = "thomastown-short-welcome-2026-07-27";
const EXCLUDED = "+61400101132";
const TEMPLATE = "Hi {name}, gate access is active at 337 Settlement Rd, Thomastown. Call 0414 371 302 to open. Caller ID must be visible. Help: 0400 101 132.";
const execute = process.argv.includes("--execute");
const statusOnly = process.argv.includes("--status");

const tools = path.join(process.env.APPDATA || "", "npm", "node_modules", "firebase-tools", "lib");
const auth = require(path.join(tools, "auth.js"));
const api = require(path.join(tools, "api.js"));
const account = auth.getGlobalDefaultAccount();
if (!account?.tokens?.refresh_token) throw new Error("Firebase CLI is not authenticated");
const adc = path.join(os.tmpdir(), `rjl-adc-${process.pid}-${crypto.randomBytes(8).toString("hex")}.json`);
fs.writeFileSync(adc, JSON.stringify({type:"authorized_user",client_id:api.clientId(),client_secret:api.clientSecret(),refresh_token:account.tokens.refresh_token}), {mode:0o600,flag:"wx"});
process.env.GOOGLE_APPLICATION_CREDENTIALS = adc;
process.on("exit", () => { try { fs.unlinkSync(adc); } catch {} });
const admin = require("../functions/node_modules/firebase-admin");
admin.initializeApp({projectId:PROJECT_ID});
const {getFirestore} = require("../functions/node_modules/firebase-admin/lib/firestore/index.js");
const db = getFirestore(admin.app(), DATABASE_ID);

const digest = value => crypto.createHash("sha256").update(value).digest("hex");
function normalize(raw) {
  let d=String(raw||"").trim().replace(/[\s()-]/g,"");
  if(d.startsWith("+"))d=d.slice(1); else if(d.startsWith("00"))d=d.slice(2);
  if(/^04\d{8}$/.test(d))d=`61${d.slice(1)}`; else if(/^4\d{8}$/.test(d))d=`61${d}`;
  return /^614\d{8}$/.test(d)?`+${d}`:null;
}
function ms(v) { if(v==null)return null; if(typeof v.toMillis==="function")return v.toMillis(); const n=Number(v); return Number.isFinite(n)?n:null; }
function firstName(raw) {
  const m=String(raw||"").trim().match(/[A-Za-z][A-Za-z'-]*/);
  if(!m)return null;
  const msg=TEMPLATE.replace("{name}",m[0]);
  return msg.length<=160 && /^[\x20-\x7E]*$/.test(msg)?m[0]:null;
}
async function main() {
  const [accounts, credentials, allCallers, allQueue] = await Promise.all([
    db.collection("clientAccounts").get(),
    db.collection("gsmDeviceCredentials").get(),
    db.collectionGroup("gsmCallers").get(),
    db.collection("gsmSmsQueue").get()
  ]);
  const credByAccount=new Map();
  for(const d of credentials.docs){const a=String(d.data().accountId||""); if(a&&d.data().enabled!==false)(credByAccount.get(a)||credByAccount.set(a,[]).get(a)).push(d);}
  const matches=accounts.docs.filter(d=>{
    const x=d.data(); const site=[x.siteName,x.name,x.address,x.siteAddress,x.propertyName,x.location].join(" ").toLowerCase();
    const devices=credByAccount.get(d.id)||[];
    return site.includes("thomastown") || devices.some(v=>`${v.id} ${v.data().deviceName||""}`.toLowerCase().includes("thomastown"));
  });
  if(matches.length!==1)throw new Error(`Expected one credential/site-linked Thomastown account, found ${matches.length}`);
  const siteAccount=matches[0], accountId=siteAccount.id;
  const devices=(credByAccount.get(accountId)||[]).sort((a,b)=>a.id.localeCompare(b.id));
  if(!devices.length)throw new Error("No enabled Thomastown device");
  const deviceId=devices[0].id;
  const queue=allQueue.docs.filter(d=>String(d.data().accountId||"")===accountId);

  let superseded=0;
  const oldJobs=queue.filter(d=>{
    const x=d.data(), s=String(x.status||"").toLowerCase(), event=String(x.eventType||"");
    return ["queued","sending"].includes(s) && event!==CAMPAIGN_ID &&
      (event.startsWith("welcome-") || String(x.message||"").includes("Alexa") || Number(x.partCount||0)>1);
  });
  if(execute && oldJobs.length){
    for(let i=0;i<oldJobs.length;i+=400){const b=db.batch(); for(const d of oldJobs.slice(i,i+400))b.update(d.ref,{status:"failed",lastError:"SUPERSEDED_BY_SHORT_WELCOME",failedAt:admin.firestore.FieldValue.serverTimestamp(),leaseUntil:null}); await b.commit();}
  }
  superseded=oldJobs.length;

  const siteCallers=allCallers.docs.filter(d=>d.ref.parent.parent?.id===accountId);
  const now=Date.now(), counts={callerRecordsFound:siteCallers.length,activeValidCallers:0,invalidCallers:0,expiredCallers:0,duplicates:0,excludedAlreadySentNumbers:0,disabledCallers:0};
  const seen=new Set(), recipients=[];
  for(const d of siteCallers.sort((a,b)=>a.id.localeCompare(b.id))){
    const x=d.data(), number=normalize(x.phoneNumberE164||x.phoneNumber), name=firstName(x.displayName||x.name);
    const from=ms(x.validFrom), until=ms(x.validUntil);
    if(!number||!name){counts.invalidCallers++;continue;}
    if(x.enabled===false){counts.disabledCallers++;continue;}
    if((from!=null&&now<from)||(until!=null&&now>until)){counts.expiredCallers++;continue;}
    counts.activeValidCallers++;
    if(seen.has(number)){counts.duplicates++;continue;} seen.add(number);
    if(number===EXCLUDED){counts.excludedAlreadySentNumbers++;continue;}
    recipients.push({doc:d,number,name,message:TEMPLATE.replace("{name}",name)});
  }
  const jobs=recipients.map(r=>({...r,jobId:digest(`${CAMPAIGN_ID}:${r.number}`)}));
  const existing=jobs.length?await db.getAll(...jobs.map(j=>db.collection("gsmSmsQueue").doc(j.jobId))):[];
  const existingIds=new Set(existing.filter(d=>d.exists).map(d=>d.id));
  const create=jobs.filter(j=>!existingIds.has(j.jobId));
  if(execute){
    for(let i=0;i<create.length;i+=400){const b=db.batch(); for(const j of create.slice(i,i+400)){b.create(db.collection("gsmSmsQueue").doc(j.jobId),{accountId,deviceId,callerId:j.doc.id,phoneNumberE164:j.number,displayName:j.name,message:j.message,eventType:CAMPAIGN_ID,campaignId:CAMPAIGN_ID,dedupKey:j.jobId,partIndex:0,partCount:1,status:"queued",attemptCount:0,createdAt:admin.firestore.FieldValue.serverTimestamp(),sentAt:null,lastError:null,nextAttemptAt:null}); b.set(j.doc.ref,{phoneNumberE164:j.number,welcomeSmsQueuedAt:admin.firestore.FieldValue.serverTimestamp(),welcomeCampaignKey:j.jobId,welcomePartCount:1},{merge:true});} await b.commit();}
  }
  const campaign=queue.filter(d=>String(d.data().eventType||"")===CAMPAIGN_ID);
  const states={queued:0,sending:0,sent:0,failed:0};
  for(const d of campaign){const s=String(d.data().status||"").toLowerCase();if(Object.hasOwn(states,s))states[s]++;}
  console.log(JSON.stringify({mode:statusOnly?"status":execute?"execute":"dry-run",accountId,sourceCollection:`clientAccounts/${accountId}/gsmCallers`,deviceId,allGsmCallerRecords:allCallers.size,...counts,recipientsToSend:recipients.map(r=>r.number),recipientsFound:recipients.length,recipientsQueued:execute?create.length:0,existingDeterministicJobs:jobs.length-create.length,supersededOldJobs:superseded,campaignStates:states,failed:campaign.filter(d=>String(d.data().status||"").toLowerCase()==="failed").map(d=>({number:d.data().phoneNumberE164,reason:d.data().lastError||"unknown"}))},null,2));
}
main().catch(e=>{console.error(e.stack||e);process.exitCode=1;});
