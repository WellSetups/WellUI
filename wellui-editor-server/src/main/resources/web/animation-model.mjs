import {flatten} from './studio-model.mjs';
export const properties = ['TRANSLATION_X','TRANSLATION_Y','SCALE_X','SCALE_Y','ROTATION_Z','OPACITY','VISIBILITY'];
export const labels = {TRANSLATION_X:'Translation X · px',TRANSLATION_Y:'Translation Y · px',SCALE_X:'Scale X · ×',SCALE_Y:'Scale Y · ×',ROTATION_Z:'Rotation Z · °',OPACITY:'Opacity · 0–1',VISIBILITY:'Visible · 0/1'};
export const easings = ['LINEAR','EASE_IN','EASE_OUT','EASE_IN_OUT'];
export const names = {LINEAR:'Linear',EASE_IN:'Ease in',EASE_OUT:'Ease out',EASE_IN_OUT:'Ease in out'};
export const baseline = p => ['SCALE_X','SCALE_Y','OPACITY','VISIBILITY'].includes(p)?1:0;
export const items = doc => doc.animations?.items??[];
export const ensure = doc => doc.animations??={schemaVersion:1,items:[]};
const uid = ()=>'animation-'+crypto.randomUUID().slice(0,12);
const copy = x=>structuredClone(x);
export const find = (doc,id)=>items(doc).find(a=>a.id===id);
export function create(doc,name='New animation') {
  if(items(doc).length>=32)throw new Error('A project supports 32 animations.');
  const a={id:uid(),name,duration:20,loop:'NONE',end:'HOLD_FINAL',tracks:[]};ensure(doc).items.push(a);return a;
}
export function duplicate(doc,id) {const source=find(doc,id);if(!source)throw new Error('Select an animation.');const a=create(doc,source.name+' copy');Object.assign(a,copy(source),{id:a.id,name:a.name});return a;}
export function remove(doc,id) {
  const refs=(doc.logic?.nodes??[]).filter(n=>n.settings.animation?.value===id);
  if(refs.length)throw new Error(`Animation is referenced by ${refs.length} Logic node(s). Remove or change those references first.`);
  ensure(doc).items=items(doc).filter(a=>a.id!==id);
}
export function addTrack(doc,id,component,property) {
  const a=find(doc,id);if(!a)throw new Error('Create an animation first.');
  const old=a.tracks.find(t=>t.component===component&&t.property===property);if(old)return old;
  if(items(doc).reduce((n,a)=>n+a.tracks.length,0)>=128)throw new Error('A project supports 128 tracks.');
  const track={component,property,keyframes:[{tick:0,value:baseline(property),easing:'LINEAR'}]};a.tracks.push(track);return track;
}
export function validValue(p,v) {return Number.isFinite(v)&&(p==='VISIBILITY'?[0,1].includes(v):p==='OPACITY'?v>=0&&v<=1:p.startsWith('SCALE_')?v>=.01&&v<=16:p==='ROTATION_Z'?Math.abs(v)<=3600:p.startsWith('TRANSLATION_')&&Math.abs(v)<=16384);}
export function setKey(doc,id,index,tick,value,easing='LINEAR') {
  const a=find(doc,id),t=a?.tracks[index];if(!t)throw new Error('Select a track.');
  if(!Number.isInteger(tick)||tick<0||tick>a.duration||!validValue(t.property,value)||!easings.includes(easing))throw new Error('Use a valid tick, value and easing.');
  const existing=t.keyframes.find(k=>k.tick===tick);
  if(!existing&&(t.keyframes.length>=64||items(doc).flatMap(a=>a.tracks).reduce((n,t)=>n+t.keyframes.length,0)>=2048))throw new Error('Keyframe limit: 64 per track, 2048 per project.');
  if(existing)Object.assign(existing,{value,easing});else t.keyframes.push({tick,value,easing});
  t.keyframes.sort((a,b)=>a.tick-b.tick);
}
export function moveKeys(doc,id,selection,delta) {
  const a=find(doc,id), draft=copy(a.tracks),chosen=new Set(selection.map(k=>`${k.track}:${k.tick}`));
  for(const [i,t] of draft.entries()) {
    const times=new Set();
    for(const k of t.keyframes) {if(chosen.has(`${i}:${k.tick}`))k.tick+=Math.round(delta);if(k.tick<0||k.tick>a.duration||times.has(k.tick))throw new Error('Keyframes must use unique ticks inside the animation duration.');times.add(k.tick);}
    t.keyframes.sort((x,y)=>x.tick-y.tick);
  }
  a.tracks=draft;
}
export function deleteKeys(doc,id,selection) {
  const a=find(doc,id);for(const [i,t] of a.tracks.entries())t.keyframes=t.keyframes.filter(k=>!selection.some(s=>s.track===i&&s.tick===k.tick));
}
export function copyKeys(doc,id,selection) {const a=find(doc,id);return selection.flatMap(s=>{const t=a.tracks[s.track],k=t?.keyframes.find(k=>k.tick===s.tick);return k?[{component:t.component,property:t.property,...copy(k)}]:[];});}
export function pasteKeys(doc,id,data,tick) {
  if(!data?.length)return[];
  const draft=copy(doc),a=find(draft,id),selected=[],origin=Math.min(...data.map(k=>k.tick));
  if(!a)throw new Error('Select an animation.');
  for(const k of data){
    let t=a.tracks.find(t=>t.component===k.component&&t.property===k.property);
    if(!t){t=addTrack(draft,id,k.component,k.property);t.keyframes=[];}
    const index=a.tracks.indexOf(t),time=tick+k.tick-origin;
    if(t.keyframes.some(x=>x.tick===time))throw new Error('Paste would overwrite a keyframe. Choose an empty tick.');
    setKey(draft,id,index,time,k.value,k.easing);selected.push({track:index,tick:time});
  }
  find(doc,id).tracks=a.tracks;
  return selected;
}
export function ease(e,t) {t=Math.max(0,Math.min(1,t));return e==='EASE_IN'?t*t:e==='EASE_OUT'?t*(2-t):e==='EASE_IN_OUT'?(t<.5?2*t*t:1-(-2*t+2)**2/2):t;}
export function sample(track,tick) {
  const keys=track.keyframes;if(!keys.length)return baseline(track.property);if(tick<=keys[0].tick)return keys[0].value;
  for(let i=1;i<keys.length;i++){const a=keys[i-1],b=keys[i];if(tick<b.tick)return track.property==='VISIBILITY'?a.value:a.value+(b.value-a.value)*ease(a.easing,(tick-a.tick)/(b.tick-a.tick));}
  return keys.at(-1).value;
}
export const identity=()=>({a:1,b:0,c:0,d:1,x:0,y:0});
export function multiply(m,n) {return {a:m.a*n.a+m.c*n.b,b:m.b*n.a+m.d*n.b,c:m.a*n.c+m.c*n.d,d:m.b*n.c+m.d*n.d,x:m.a*n.x+m.c*n.y+m.x,y:m.b*n.x+m.d*n.y+m.y};}
export function local(cx,cy,tx,ty,sx,sy,degrees) {const r=degrees*Math.PI/180,a=Math.cos(r)*sx,b=Math.sin(r)*sx,c=-Math.sin(r)*sy,d=Math.cos(r)*sy;return {a,b,c,d,x:cx+tx-a*cx-c*cy,y:cy+ty-b*cx-d*cy};}
export const point=(m,x,y)=>({x:m.a*x+m.c*y+m.x,y:m.b*x+m.d*y+m.y});
export function scene(doc,id,tick,quantized=true) {
  const a=find(doc,id),values=new Map((a?.tracks??[]).map(t=>[t.component+':'+t.property,sample(t,tick)])),map=new Map();
  return flatten(doc).map(item=>{const n=item.node,get=p=>values.get(n.id+':'+p)??baseline(p),parent=map.get(item.parent);
    const matrix=multiply(parent?.matrix??identity(),local(item.x+item.width/2,item.y+item.height/2,get('TRANSLATION_X'),get('TRANSLATION_Y'),get('SCALE_X'),get('SCALE_Y'),get('ROTATION_Z')));
    const alpha=(parent?.alpha??1)*n.style.opacity*get('OPACITY')*get('VISIBILITY'),result={...item,matrix,alpha,opacity:quantized?Math.round(alpha*8)/8:alpha};map.set(n.id,result);return result;});
}
export function hit(scene,x,y) {
  for(const item of [...scene].reverse()){if(item.opacity<=0)continue;const m=item.matrix,det=m.a*m.d-m.b*m.c,px=(m.d*(x-m.x)-m.c*(y-m.y))/det,py=(-m.b*(x-m.x)+m.a*(y-m.y))/det;
    if(px>=item.x&&py>=item.y&&px<item.x+item.width&&py<item.y+item.height)return item.node.type==='button'?item.node:null;}
  return null;
}
export const presets=['Fade In','Fade Out','Slide In Left','Slide In Right','Scale In','Scale Out'];
export function preset(doc,id,component,name) {
  const a=find(doc,id),duration=a.duration,definitions=name==='Fade In'?[['OPACITY',0,1]]:name==='Fade Out'?[['OPACITY',1,0]]:name==='Slide In Left'?[['TRANSLATION_X',-80,0]]:name==='Slide In Right'?[['TRANSLATION_X',80,0]]:name==='Scale In'?[['SCALE_X',.8,1],['SCALE_Y',.8,1]]:[['SCALE_X',1,.8],['SCALE_Y',1,.8]];
  for(const [property,from,to] of definitions){const t=addTrack(doc,id,component,property);t.keyframes=[{tick:0,value:from,easing:'EASE_IN_OUT'},{tick:duration,value:to,easing:'LINEAR'}];}
}
export function renameComponent(doc,oldId,id) {items(doc).forEach(a=>a.tracks.forEach(t=>{if(t.component===oldId)t.component=id;}));}
export function copyComponents(doc,ids) {return items(doc).map(a=>({...copy(a),tracks:copy(a.tracks.filter(t=>ids.has(t.component)))})).filter(a=>a.tracks.length);}
export function pasteAnimations(doc,data,map) {
  const ids={};for(const source of data??[]){const a=create(doc,source.name+' copy');ids[source.id]=a.id;Object.assign(a,copy(source),{id:a.id,name:a.name});a.tracks.forEach(t=>{t.component=map[t.component]??t.component;});}return ids;
}
export function independent(doc) {const roots=new Set(items(doc).flatMap(a=>a.tracks.map(t=>t.component)));(doc.logic?.nodes??[]).filter(n=>['set_visible','set_text','set_opacity'].includes(n.type)).forEach(n=>roots.add(n.settings.component?.value));const result=new Set();for(const p of flatten(doc))if(roots.has(p.node.id)||result.has(p.parent)||p.node.type==='button')result.add(p.node.id);return result;}
export function validation(doc) {
  const issues=[],ids=new Set(),components=new Set(flatten(doc).map(p=>p.node.id));let count=0,keys=0;
  const issue=(code,id='',message)=>issues.push({code:'animation.'+code,animationId:id,message,level:'error'});
  if(doc.animations&&doc.animations.schemaVersion!==1)issue('schema');if(items(doc).length>32)issue('limit');
  for(const a of items(doc)){if(!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(a.id)||ids.has(a.id))issue('id',a.id);ids.add(a.id);
    if(!a.name?.trim()||a.name.length>128||/[\x00-\x1f\x7f-\x9f]/.test(a.name))issue('name',a.id);
    if(!Number.isInteger(a.duration)||a.duration<1||a.duration>1200)issue('duration',a.id);
    if(!['NONE','LOOP'].includes(a.loop)||!['RESTORE','HOLD_FINAL'].includes(a.end))issue('json',a.id);
    if(!a.tracks.length)issue('tracks',a.id);const seen=new Set();count+=a.tracks.length;
    for(const t of a.tracks){if(!components.has(t.component))issue('component',a.id,`Animation “${a.name}” targets missing component “${t.component}”. Remove or retarget its track.`);
      const key=t.component+':'+t.property;if(seen.has(key))issue('track_duplicate',a.id);seen.add(key);if(!properties.includes(t.property))issue('value',a.id);
      if(!t.keyframes.length||t.keyframes.length>64)issue('keyframes',a.id);keys+=t.keyframes.length;let previous=-1;
      for(const k of t.keyframes){if(!Number.isInteger(k.tick)||k.tick<=previous||k.tick>a.duration||k.tick<0)issue('time',a.id);previous=k.tick;if(!validValue(t.property,k.value)||!easings.includes(k.easing))issue('value',a.id);}
    }
  }
  if(count>128||keys>2048)issue('limit');const layers=independent(doc).size;if(items(doc).length&&layers>16)issue('screen_layers','',`This project requires ${layers} independent components; SCREEN supports 16.`);
  for(const n of doc.logic?.nodes??[])if(n.settings.animation&&!ids.has(n.settings.animation.value))issues.push({code:'animation.reference',nodeId:n.id,level:'error'});
  if(items(doc).length){const required=independent(doc);for(const p of flatten(doc))if(required.has(p.node.id)&&(p.x<0||p.y<0||p.x+p.width>doc.width||p.y+p.height>doc.height))issue('bounds','',`Component “${p.node.id}” needs its authored bounds inside the canvas. Use translation keyframes for off-canvas entrances.`);}
  return issues;
}

export function demo(name,asset,target='animation-destination') {
  const style=(background='#1b2a3d',color='#eef5ff',fontSize=20)=>({background,color,fontSize,opacity:1});
  const node=(id,type,x,y,width,height,extra={})=>({id,type,layout:{x,y,width,height,anchor:'top_left'},style:style(),...extra});
  const doc={schemaVersion:1,id:'animation-demo',name,width:900,height:600,children:[node('menu','panel',110,70,680,460,{children:[
    node('title','text',40,36,570,55,{style:style('#1b2a3d','#eef5ff',32),text:'A little motion. A clear purpose.'}),
    node('crest','image',285,120,110,110,{asset}),
    node('continue','button',40,320,270,64,{style:style('#79ddbb','#13332b'),text:'Continue →',action:{type:'close_ui',value:''}}),
    node('press','button',370,320,270,64,{text:'Try a press',action:{type:'close_ui',value:''}})
  ]})],animations:{schemaVersion:1,items:[]},logic:{schemaVersion:1,nodes:[],edges:[],variables:[]}};
  const track=(component,property,keys)=>({component,property,keyframes:keys.map(([tick,value,easing='EASE_IN_OUT'])=>({tick,value,easing}))});
  doc.animations.items=[{id:'menu_enter',name:'Menu entrance',duration:24,loop:'NONE',end:'HOLD_FINAL',tracks:[
    track('menu','OPACITY',[[0,0],[12,1]]),track('menu','SCALE_X',[[0,.88],[18,1]]),track('menu','SCALE_Y',[[0,.88],[18,1]]),
    track('title','TRANSLATION_Y',[[0,-35],[16,0]]),track('continue','TRANSLATION_X',[[0,-90],[6,-90],[20,0]]),
    track('continue','OPACITY',[[0,0],[6,0],[20,1]]),track('press','TRANSLATION_X',[[0,90],[10,90],[24,0]]),track('press','OPACITY',[[0,0],[10,0],[24,1]])]},
    {id:'menu_exit',name:'Menu exit',duration:16,loop:'NONE',end:'HOLD_FINAL',tracks:[track('menu','TRANSLATION_X',[[0,0],[16,150]]),track('menu','OPACITY',[[0,1],[16,0]])]},
    {id:'button_press',name:'Button press',duration:8,loop:'NONE',end:'RESTORE',tracks:[track('press','SCALE_X',[[0,1],[3,.93],[8,1]]),track('press','SCALE_Y',[[0,1],[3,.93],[8,1]])]}];
  const setting=v=>({type:'STRING',value:v}),n=(id,type,x,y,settings={})=>({id,type,x,y,settings}),edge=(from,to)=>({from,output:'next',to,input:'in'});
  doc.logic.nodes=[n('open','on_open',40,40),n('enter','play_animation',340,40,{animation:setting('menu_enter')}),
    n('click','on_click',40,230,{component:setting('continue')}),n('exit','play_animation',340,230,{animation:setting('menu_exit')}),
    n('complete','on_animation_complete',40,420,{animation:setting('menu_exit')}),n('next','open_ui',340,420,{project:setting(target)}),
    n('click_press','on_click',40,610,{component:setting('press')}),n('press_anim','play_animation',340,610,{animation:setting('button_press')})];
  doc.logic.edges=[edge('open','enter'),edge('click','exit'),edge('complete','next'),edge('click_press','press_anim')];return doc;
}
