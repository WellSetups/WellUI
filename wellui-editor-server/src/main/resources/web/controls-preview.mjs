import {preview,setting} from './controls-model.mjs';

// Shared design/timeline decoration; preview state is never serialized.
export function paint(element,control,label,state='NORMAL'){
  if(!control)return;
  const p=preview(control,label,state),make=(cls,text='')=>{
    const n=document.createElement('span');n.className=cls;n.textContent=text;element.append(n);return n;
  };
  element.classList.add('control-preview');element.dataset.control=control.kind;element.dataset.state=p.state;
  element.replaceChildren();
  if(['INPUT','SELECT','SLIDER','CHECKBOX','TOGGLE'].includes(control.kind))make('control-label',p.label);
  if(control.kind==='CHECKBOX')make('control-check',p.value?'✓':'');
  if(control.kind==='TOGGLE'){const track=make('control-switch');track.dataset.checked=String(p.value);const knob=document.createElement('span');track.append(knob);}
  if(control.kind==='SLIDER'){const track=make('control-track'),knob=document.createElement('span');knob.style.left=(p.fraction*100)+'%';track.append(knob);}
  if(control.kind==='SELECT')make('control-arrow','▾');
  if(control.kind==='SCROLL'&&setting(control,'overflow','SCROLL')==='SCROLL'){const bar=make('control-scrollbar');bar.dataset.axis=setting(control,'axis','y');}
  if(control.kind==='FORM')make('control-group-caption','Form');
}

// Design-space clip rectangle shared by both previews at the authored scroll origin.
export function clippedBounds(doc,item,items){
  let parent=item.parent;
  while(parent){const p=items.find(i=>i.node.id===parent),c=doc.controls?.[parent];if(!p)break;
    if(c?.kind==='SCROLL'&&setting(c,'overflow','SCROLL')!=='VISIBLE')return p;
    parent=p.parent;
  }
  return null;
}
