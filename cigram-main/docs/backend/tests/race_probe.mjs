// Reproduces the lost-message race of the 18.x chat Worker: 24 sends to ONE thread issued at the same time.
//   node race_probe.mjs <worker.mjs>      (the 18.x bundle printed stored=1; the 19.0 bundle prints stored=24)
// The in-memory storage yields on every put (like an await on real storage), which is what lets the interleaving happen.
import { webcrypto } from "node:crypto";
const mod = await import(process.argv[2]); const { ChatHub } = mod; const worker = mod.default;
class Mem { constructor(){this.m=new Map();this.alarm=null;} async get(k){return this.m.has(k)?structuredClone(this.m.get(k)):undefined;} async put(k,v){await Promise.resolve();this.m.set(k,structuredClone(v));} async delete(k){Array.isArray(k)?k.forEach(x=>this.m.delete(x)):this.m.delete(k);} async list(o={}){let ks=[...this.m.keys()].filter(k=>!o.prefix||k.startsWith(o.prefix)).sort(); if(o.start)ks=ks.filter(k=>k>=o.start); if(o.reverse)ks.reverse(); if(o.limit)ks=ks.slice(0,o.limit); return new Map(ks.map(k=>[k,structuredClone(this.m.get(k))]));} async getAlarm(){return this.alarm;} async setAlarm(t){this.alarm=t;} }
const st={storage:new Mem(),getWebSockets:()=>[],acceptWebSocket(){},waitUntil(){}};
const env={CHAT_KEY:Buffer.from(webcrypto.getRandomValues(new Uint8Array(32))).toString("base64"),ADMIN_TOKEN:"adm",JWT_SECRET:"s"};
const hub=new ChatHub(st,env);
env.CHAT_HUB={idFromName:()=>"h",get:()=>({fetch:(u,i)=>hub.fetch(new Request(u,i))})};
env.ADS_CORE={idFromName:()=>"c",get:()=>({fetch:async()=>new Response(JSON.stringify({ok:true,advertiser:{id:"a"},settings:{hours:{enabled:false}}}))})};
const send=(thread,i)=>hub.fetch(new Request("https://h/op",{method:"POST",body:JSON.stringify({op:"send",p:{side:i%2?"admin":"user",thread,client_id:"c"+i,type:"text",text:"m"+i,name:"N"}})}));
await hub.fetch(new Request("https://h/op",{method:"POST",body:JSON.stringify({op:"thread_open",p:{user_key:"u_race",name:"N"}})}));
await Promise.all(Array.from({length:24},(_, i)=>send("u_race",i)));
const rows=[...(await st.storage.list({prefix:"m:u_race:"})).values()];
const t=await st.storage.get("t:u_race");
console.log(JSON.stringify({sent:24,stored:rows.length,distinct_seq:new Set(rows.map(r=>r.seq)).size,thread_last_seq:t.last_seq}));
