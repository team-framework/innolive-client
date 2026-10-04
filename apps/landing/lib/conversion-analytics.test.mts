import assert from "node:assert/strict";
import { test } from "node:test";
import { nextVisit, parseConversionEvent, trackConversion } from "./conversion-analytics.ts";
const event = {version:1,eventId:crypto.randomUUID(),visitId:crypto.randomUUID(),sequence:1,event:"landing_viewed",properties:{locale:"ko",browser:"safari"}};
test("only event catalog and bounded anonymous properties pass",()=>{
 assert.deepEqual(parseConversionEvent(event),event);
 for(const change of [{email:"private@example.test"},{event:"unknown"},{sequence:0},{sequence:1.5},{eventId:"invalid"},{properties:{...event.properties,email:"private@example.test"}},{properties:{locale:"ko",browser:"raw-agent"}}]) assert.equal(parseConversionEvent({...event,...change}),null);
});
test("visit survives navigation and reload; expires after inactivity; sequence orders late beacons",()=>{
 const data=new Map<string,string>();const storage={getItem:(key:string)=>data.get(key)??null,setItem:(key:string,value:string)=>{data.set(key,value);}};
 const first=nextVisit(storage,100);const next=nextVisit(storage,200);assert.equal(first.id,next.id);assert.equal(next.sequence,2);
 const expired=nextVisit(storage,1_800_201);assert.notEqual(expired.id,first.id);assert.equal(expired.sequence,1);
});
test("blocked storage retains in-memory visit",()=>{
 const storage={getItem:()=>{throw Error();},setItem:()=>{throw Error();}};
 const first=nextVisit(storage,2_000_000);const next=nextVisit(storage,2_000_001);assert.equal(first.id,next.id);assert.equal(next.sequence,first.sequence+1);
});

test("DNT suppresses events and blocked sessionStorage still sends no account data",(t)=>{
 const previousWindow=Object.getOwnPropertyDescriptor(globalThis,"window");
 const previousDNT=Object.getOwnPropertyDescriptor(navigator,"doNotTrack");
 t.after(()=>{if(previousWindow)Object.defineProperty(globalThis,"window",previousWindow);else delete (globalThis as unknown as Record<string,unknown>).window;if(previousDNT)Object.defineProperty(navigator,"doNotTrack",previousDNT);else delete (navigator as unknown as Record<string,unknown>).doNotTrack;});
 Object.defineProperty(globalThis,"window",{value:{get sessionStorage(){throw Error("blocked");}},configurable:true});
 const sent:string[]=[];
 const fetch=t.mock.method(globalThis,"fetch",async (_url:unknown,init?:RequestInit)=>{sent.push(init?.body as string);return new Response(null,{status:204});});
 Object.defineProperty(navigator,"doNotTrack",{value:"1",configurable:true});trackConversion("signup_completed","ko");assert.equal(fetch.mock.callCount(),0);
 Object.defineProperty(navigator,"doNotTrack",{value:"0",configurable:true});trackConversion("signup_completed","ko");assert.equal(fetch.mock.callCount(),1);assert.ok(parseConversionEvent(JSON.parse(sent[0])));
});
