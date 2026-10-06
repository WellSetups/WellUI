import {clone,fingerprint} from './studio-model.mjs';

// A server-side rename changes the saved base, but must preserve other local edits.
export function acceptRename(history, saved) {
  if(history.doc.id!==saved.id)return false;
  history.execute('Rename project', draft=>{draft.name=saved.name;});
  history.saved=fingerprint(saved);
  return true;
}

function canonical(document) {
  if(!document)return '';
  const value=clone(document);
  if(!Object.keys(value.controls??{}).length)delete value.controls;
  if(value.logic&&['nodes','edges','variables'].every(key=>!value.logic[key]?.length))delete value.logic;
  if(value.animations&&!value.animations.items?.length)delete value.animations;
  const sort=value=>Array.isArray(value)?value.map(sort):value&&typeof value==='object'
    ?Object.fromEntries(Object.keys(value).sort().map(key=>[key,sort(value[key])])):value;
  return JSON.stringify(sort(value));
}

// Preserve the saved base to detect concurrent edits rather than silently overwrite them.
export function chooseDraft(id, server, recovery) {
  if(recovery?.document?.id===id && fingerprint(recovery.document)!==recovery.saved) {
    if(canonical(server)!==canonical(recovery.saved?JSON.parse(recovery.saved):null))
      throw new Error('Linked project '+id+' changed on the server. Open it and reconcile the local draft before deploying.');
    return {document:clone(recovery.document),base:clone(server)};
  }
  if(!server) throw new Error('Linked project '+id+' is missing.');
  return {document:clone(server),base:clone(server)};
}

export async function collect(document, base, load) {
  const seen=new Set(), documents=[];
  async function visit(row) {
    const id=row.document.id;
    if(seen.has(id))return;
    seen.add(id);
    if(seen.size>64)throw new Error('A deployment may include at most 64 linked projects.');
    const targets=[...new Set((row.document.logic?.nodes??[]).filter(n=>n.type==='open_ui').map(n=>n.settings.project.value))].sort();
    for(const target of targets)if(!seen.has(target)) {
      const next=await load(target);
      if(next.document.id!==target)throw new Error('Linked project identity mismatch.');
      await visit(next);
    }
    documents.push(row);
  }
  await visit({document:clone(document),base:clone(base)});
  return {root:document.id,documents};
}
