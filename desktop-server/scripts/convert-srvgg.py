#!/usr/bin/env python3
"""Convert the official Real-ESRGAN SRVGG checkpoint to NCNN, without importing/executing torch.
Only whitelisted tensor metadata is unpickled; storage must be contiguous little-endian float32.
Architecture: xinntao/Real-ESRGAN, realesrgan/archs/srvgg_arch.py (BSD-3-Clause).
"""
import argparse, collections, hashlib, io, json, math, pickle, struct, zipfile
from pathlib import Path

def rebuild(storage, offset, shape, stride, *_):
    expected=[]; step=1
    for size in reversed(shape): expected.insert(0,step); step*=size
    if tuple(expected)!=tuple(stride) or offset!=0 or math.prod(shape)!=storage['count']: raise ValueError('Only contiguous complete tensors supported')
    return dict(storage,shape=shape)
class Metadata(pickle.Unpickler):
    def find_class(self,module,name):
        if (module,name)==('collections','OrderedDict'):return collections.OrderedDict
        if (module,name)==('torch._utils','_rebuild_tensor_v2'):return rebuild
        if (module,name)==('torch','FloatStorage'):return 'float32'
        raise ValueError('Unsupported pickle global: '+module+'.'+name)
    def persistent_load(self,value):
        kind,dtype,key,device,count=value
        if kind!='storage' or dtype!='float32' or not key.isdigit() or not 0<count<10_000_000:raise ValueError('Invalid storage')
        return dict(key=key,count=count)

def convert(source,target):
    source=Path(source); target=Path(target); target.parent.mkdir(parents=True,exist_ok=True)
    if source.stat().st_size>64*1024*1024:raise ValueError('Checkpoint too large')
    with zipfile.ZipFile(source) as z:
        index=next(n for n in z.namelist() if n.endswith('/data.pkl')); prefix=index[:-8]
        if z.getinfo(index).file_size>1024*1024:raise ValueError('Metadata too large')
        root=Metadata(io.BytesIO(z.read(index))).load(); state=root.get('params_ema',root.get('params'))
        if not isinstance(state,dict):raise ValueError('Missing parameters')
        indices=sorted(int(k.split('.')[1]) for k,v in state.items() if k.endswith('.weight') and len(v['shape'])==4)
        if len(indices)!=34 or indices!=list(range(0,67,2)):raise ValueError('Expected general-x4v3 architecture')
        lines=['Input input 0 1 data','Split split 1 2 data skip conv_in']; blobs=3; last='conv_in'; weights=bytearray(); expected=set()
        def raw(key,shape):
            expected.add(key);v=state[key]
            if tuple(v['shape'])!=tuple(shape):raise ValueError('Unexpected tensor shape: '+key)
            entry=prefix+'data/'+v['key'];size=v['count']*4
            if z.getinfo(entry).file_size!=size:raise ValueError('Storage size mismatch')
            return z.read(entry)
        for layer in indices:
            output=48 if layer==indices[-1] else 64; input_channels=3 if layer==0 else 64
            count=output*input_channels*9; blob='conv_'+str(layer)
            lines.append(f'Convolution conv{layer} 1 1 {last} {blob} 0={output} 1=3 4=1 5=1 6={count}')
            weights+=struct.pack('<I',0)+raw(f'body.{layer}.weight',(output,input_channels,3,3))+raw(f'body.{layer}.bias',(output,));blobs+=1;last=blob
            if layer!=indices[-1]:
                blob='act_'+str(layer)
                lines.append(f'PReLU prelu{layer} 1 1 {last} {blob} 0=64');weights+=raw(f'body.{layer+1}.weight',(64,));blobs+=1;last=blob
        if set(state)!=expected:raise ValueError('Unexpected parameters')
        lines.extend([f'PixelShuffle shuffle 1 1 {last} shuffled 0=4','Interp nearest 1 1 skip base 0=1 1=4.0 2=4.0','BinaryOp residual 2 1 shuffled base output 0=0']);blobs+=3
        target.with_suffix('.param').write_text(f'7767517\n{len(lines)} {blobs}\n'+'\n'.join(lines)+'\n')
        target.with_suffix('.bin').write_bytes(weights)
    manifest={'model':'realesr-general-x4v3','sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'paramSha256':hashlib.sha256(target.with_suffix('.param').read_bytes()).hexdigest(),'weightsSha256':hashlib.sha256(weights).hexdigest(),'scale':4,'layers':len(lines),'source':'https://github.com/xinntao/Real-ESRGAN'}
    target.with_suffix('.json').write_text(json.dumps(manifest,indent=2)+'\n');return manifest
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('source');parser.add_argument('target');args=parser.parse_args();print(json.dumps(convert(args.source,args.target)))
