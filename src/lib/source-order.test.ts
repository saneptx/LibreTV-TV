import { expect, it } from 'vitest';
import { compareSourceResolution } from './source-order';
it('换源按原始分辨率降序，当前低清源不置顶，未知和不可用靠后',()=>{
  const list=[{id:'current',height:720,ok:true,ms:10},{id:'unknown',ok:true,ms:1},{id:'4k',height:2160,ok:true,ms:200},
    {id:'failed',height:2160,ok:false,ms:1},{id:'1080-slow',height:1080,ok:true,ms:300},{id:'1080-fast',height:1080,ok:true,ms:100}];
  expect(list.sort(compareSourceResolution).map(v=>v.id)).toEqual(['4k','1080-fast','1080-slow','current','unknown','failed']);
});
it('同分辨率和速度保持原顺序，异常探测值视为未知',()=>{
  expect(compareSourceResolution({height:1080,ms:100},{height:1080,ms:100})).toBe(0);
  expect(compareSourceResolution({height:NaN,ms:-1},{height:720,ms:100})).toBeGreaterThan(0);
});
