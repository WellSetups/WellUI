// Optional schema-1 behavior descriptors. Runtime values never enter the saved document.
export const kinds=['input','checkbox','toggle','slider','select','form','scroll'];
export const states=['NORMAL','HOVER','FOCUSED','ACTIVE','DISABLED','INVALID'];
export const scalar=(type,value)=>({type,value:String(value)});
export const setting=(control,key,fallback)=>{
  const s=control?.settings?.[key];return !s?fallback:s.type==='BOOLEAN'?s.value==='true':s.type==='NUMBER'?Number(s.value):s.value;
};
export const valueType=control=>['CHECKBOX','TOGGLE','FORM'].includes(control.kind)?'BOOLEAN':['SLIDER','SCROLL'].includes(control.kind)?'NUMBER':'STRING';
export function attach(doc,node,kind){
  if(!kinds.includes(kind))return;
  const control={kind:kind.toUpperCase(),settings:{},options:[]};
  if(kind==='input')Object.assign(control.settings,{placeholder:scalar('STRING','Enter text…'),maxLength:scalar('NUMBER',128)});
  if(kind==='slider')Object.assign(control.settings,{min:scalar('NUMBER',0),max:scalar('NUMBER',100),step:scalar('NUMBER',1),initial:scalar('NUMBER',50)});
  if(kind==='select')control.options=[{value:'first',label:'First option',disabled:false},{value:'second',label:'Second option',disabled:false}];
  if(kind==='scroll')Object.assign(control.settings,{overflow:scalar('STRING','SCROLL'),axis:scalar('STRING','y'),scrollSpeed:scalar('NUMBER',40)});
  (doc.controls??={})[node.id]=control;
}
export function remove(doc,ids){if(doc.controls)ids.forEach(id=>delete doc.controls[id]);}
export function rename(doc,oldId,id){if(doc.controls?.[oldId]){doc.controls[id]=doc.controls[oldId];delete doc.controls[oldId];}}
export function validation(doc){
  const issues=[];
  for(const [id,c] of Object.entries(doc.controls??{})){
    const issue=message=>issues.push({componentId:id,code:'control.browser',message,level:'error'});
    if(!kinds.includes(c.kind.toLowerCase()))issue('Unknown control kind.');
    if(setting(c,'multiline',false))issue('Multiline input is not supported by SCREEN.');
    if(setting(c,'maxLength',128)>512||setting(c,'maxLength',128)<1)issue('Input length must be 1–512.');
    if(c.options.length>32)issue('Select supports at most 32 options.');
    if(new Set(c.options.map(o=>o.value)).size!==c.options.length)issue('Option values must be unique.');
    if(c.kind==='SLIDER'&&(setting(c,'min',0)>=setting(c,'max',100)||setting(c,'step',1)<=0))issue('Slider needs min < max and a positive step.');
  }
  return issues;
}
export function preview(control,label,state='NORMAL'){
  let value=setting(control,'initial',valueType(control)==='BOOLEAN'?false:valueType(control)==='NUMBER'?setting(control,'min',0):'');
  if(control.kind==='INPUT')label=value||setting(control,'placeholder',label);
  if(control.kind==='SELECT')label=control.options.find(o=>o.value===value)?.label??setting(control,'placeholder','Choose an option');
  if(control.kind==='SLIDER')label+=' '+value;
  const min=setting(control,'min',0),max=setting(control,'max',100);
  return {label,value,fraction:Math.max(0,Math.min(1,(Number(value)-min)/(max-min||1))),state:setting(control,'disabled',false)?'DISABLED':state};
}
