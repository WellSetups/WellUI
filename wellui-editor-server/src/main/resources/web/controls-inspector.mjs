import * as C from './controls-model.mjs';
export function controlInspector({doc,id,section,field,change,disabled}){
  const c=doc.controls?.[id];if(!c)return;
  const area=section(c.kind[0]+c.kind.slice(1).toLowerCase()+' settings');
  const prop=(key,label,type,fallback,options)=>field(area,label,C.setting(c,key,fallback),value=>change('Change '+label,d=>{
    d.controls[id].settings[key]=C.scalar(type,type==='BOOLEAN'?value==='true':value);
  }),{disabled,number:type==='NUMBER',options:type==='BOOLEAN'?['false','true']:options});
  prop('disabled','Disabled','BOOLEAN',false);
  if(!['FORM','SCROLL'].includes(c.kind)){
    prop('initial','Initial value',C.valueType(c),C.valueType(c)==='BOOLEAN'?false:C.valueType(c)==='NUMBER'?C.setting(c,'min',0):'');
    prop('required','Required','BOOLEAN',false);
    prop('message','Validation message','STRING','');
  }
  if(c.kind==='INPUT'){
    prop('placeholder','Placeholder','STRING','');prop('readOnly','Read only','BOOLEAN',false);
    prop('minLength','Minimum length','NUMBER',0);prop('maxLength','Maximum length (1–512)','NUMBER',128);
    prop('pattern','Character pattern, e.g. [A-Za-z]{1,32}','STRING','');
    const note=document.createElement('p');note.className='hint';note.textContent='SCREEN opens a native single-line text dialog. Values stay in the player’s current session. Multiline is not supported.';area.append(note);
  }
  if(c.kind==='SLIDER')for(const [key,label,value] of [['min','Minimum',0],['max','Maximum',100],['step','Step',1]])prop(key,label,'NUMBER',value);
  if(c.kind==='SELECT'){
    prop('placeholder','Placeholder','STRING','Choose an option');
    field(area,'Options: value | label | disabled (optional)',c.options.map(o=>`${o.value} | ${o.label}${o.disabled?' | disabled':''}`).join('\n'),value=>change('Edit options',d=>{
      const options=value.split('\n').filter(s=>s.trim()).map(line=>{const [value,label,flag]=line.split('|').map(s=>s.trim());return {value,label:label||value,disabled:flag==='disabled'};});
      if(!options.length||options.length>32||options.some(o=>!o.value)||new Set(options.map(o=>o.value)).size!==options.length)throw new Error('Use 1–32 unique non-empty option values.');
      d.controls[id].options=options;
    }),{multiline:true,disabled});
  }
  if(c.kind==='SCROLL'){
    prop('overflow','Overflow','STRING','SCROLL',['VISIBLE','HIDDEN','SCROLL']);
    prop('axis','Scroll direction','STRING','y',[{value:'y',label:'Vertical'},{value:'x',label:'Horizontal'}]);
    prop('scrollSpeed','Scroll step','NUMBER',40);
    const note=document.createElement('p');note.className='hint';note.textContent='Up to 32 prepared positions and 32 static children. Child buttons work. Nested viewports, controls and independent child animations are rejected. Animate the container as a whole.';area.append(note);
  }
}
