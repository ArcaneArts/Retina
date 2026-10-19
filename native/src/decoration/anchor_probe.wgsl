// Test-only complete plant/vine programs. Exact column-independent programs
// prepare and evaluate candidates in parallel, then compact in original order.
@group(0) @binding(0) var<storage,read> data: array<u32>;
@group(0) @binding(1) var<storage,read> anchors: array<vec4<u32>>;
@group(0) @binding(2) var<storage,read_write> cells: array<vec2<u32>>;
@group(0) @binding(3) var<storage,read_write> output: array<u32>;
@group(0) @binding(4) var<storage,read_write> candidates: array<vec4<u32>>;
@group(0) @binding(5) var<storage,read_write> column_lists: array<atomic<u32>>;
var<private> owner:u32;
var<private> local_column:u32;
var<private> candidate_index:u32;
var<private> out_base:u32;
var<private> used:u32;
var<private> failed:bool;
const NONE:u32=0xffffffffu;
fn column_mode()->bool{return data[24]>0u&&data[29]!=0u;}
fn list_base()->u32{return owner*(1u+256u*(1u+data[30]));}
fn add64(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{let lo=a.x+b.x;return vec2<u32>(lo,a.y+b.y+u32(lo<a.x));}
fn mul64(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{
 let p0=(a.x&65535u)*(b.x&65535u);let p1=(a.x>>16u)*(b.x&65535u);let p2=(a.x&65535u)*(b.x>>16u);
 let carry=(p0>>16u)+(p1&65535u)+(p2&65535u);
 return vec2<u32>((p0&65535u)|(carry<<16u),(a.x>>16u)*(b.x>>16u)+(p1>>16u)+(p2>>16u)+(carry>>16u)+a.x*b.y+a.y*b.x);
}
fn shr64(a:vec2<u32>,n:u32)->vec2<u32>{return vec2<u32>((a.x>>n)|(a.y<<(32u-n)),a.y>>n);}
fn next64(r:ptr<function,vec2<u32>>)->vec2<u32>{
 *r=add64(*r,vec2<u32>(0x7f4a7c15u,0x9e3779b9u));var n=*r;
 n=mul64(n^shr64(n,30u),vec2<u32>(0x1ce4e5b9u,0xbf58476du));
 n=mul64(n^shr64(n,27u),vec2<u32>(0x133111ebu,0x94d049bbu));return n^shr64(n,31u);
}
fn less64(a:vec2<u32>,b:vec2<u32>)->bool{return a.y<b.y||(a.y==b.y&&a.x<b.x);}
fn below(r:ptr<function,vec2<u32>>,n:i32)->i32{
 if(n<=1){return 0;}let a=next64(r);let d=u32(n);var rem=(a.y>>16u)%d;
 rem=(rem*65536u+(a.y&65535u))%d;rem=(rem*65536u+(a.x>>16u))%d;return i32((rem*65536u+(a.x&65535u))%d);
}
fn integer(at:u32,r:ptr<function,vec2<u32>>)->i32{
 let i=data[12]+at;let kind=data[i];let a=bitcast<i32>(data[i+1u]);let b=bitcast<i32>(data[i+2u]);
 if(kind==0u){return a;}if(kind==1u){return a+below(r,b-a+1);}if(kind==2u){let n=below(r,b-a+1)+1;return a+below(r,n);}
 let plateau=bitcast<i32>(data[i+3u]);let range=b-a;if(plateau>=range){return a+below(r,range+1);}
 let low=(range-plateau)/2;let x=below(r,low+1);return a+x+below(r,range-low+1);
}
fn column(p:vec3<i32>)->u32{
 let x=p.x-bitcast<i32>(data[0]);let z=p.z-bitcast<i32>(data[1]);let w=i32(data[2]);
 if(x<0||z<0||x>=w||z>=w){failed=true;return NONE;}return u32(z*w+x);
}
fn get_cell(key:u32)->u32{
 var slot=(key*2654435761u)&(data[7]-1u);
 for(var n=0u;n<data[7];n++){
  let c=cells[owner*data[7]+slot];if(c.x==0u){return NONE;}if(c.x==key){return c.y;}slot=(slot+1u)&(data[7]-1u);
 }failed=true;return NONE;
}
fn put_cell(key:u32,value:u32){
 var slot=(key*2654435761u)&(data[7]-1u);
 for(var n=0u;n<data[7];n++){
  let at=owner*data[7]+slot;let c=cells[at];if(c.x==0u||c.x==key){cells[at]=vec2<u32>(key,value);return;}slot=(slot+1u)&(data[7]-1u);
 }failed=true;
}
fn material(p:vec3<i32>)->u32{
 let y=p.y-bitcast<i32>(data[3]);if(y<0||y>=i32(data[4])){return 0u;}
 let col=column(p);if(col==NONE){return NONE;}let key=u32(y)*data[2]*data[2]+col+1u;
 if(column_mode()){
  let a=anchors[owner];let lx=p.x-bitcast<i32>(a.x)*16;let lz=p.z-bitcast<i32>(a.y)*16;
  if(lx>=0&&lz>=0&&lx<16&&lz<16&&u32(lz*16+lx)==local_column){
   let at=(owner*256u+local_column)*data[4]+u32(y);let live=cells[at/2u][at%2u];if(live!=0u){return live-1u;}
  }
 }else{let live=get_cell(key);if(live!=NONE){return live;}}
 if(data[31]!=0u){
  let desc=data[31]+owner*2u;let start=data[desc];let len=data[desc+1u];var lo=0u;var hi=len;
  while(lo<hi){let mid=(lo+hi)/2u;if(data[start+mid*2u]<key){lo=mid+1u;}else{hi=mid;}}
  if(lo<len&&data[start+lo*2u]==key){return data[start+lo*2u+1u];}
 }
 let d=32u+col*16u;let start=data[d];let len=data[d+1u];var lo=0u;var hi=len;
 while(lo<hi){let mid=(lo+hi)/2u;if((data[start+mid]>>16u)>u32(y)){lo=mid+1u;}else{hi=mid;}}
 if(lo==len){failed=true;return NONE;}return data[start+lo]&65535u;
}
fn write(p:vec3<i32>,m:u32){
 let col=column(p);if(col==NONE){return;}let y=p.y-bitcast<i32>(data[3]);if(y<0||y>=i32(data[4])){return;}
 if(column_mode()){let at=(owner*256u+local_column)*data[4]+u32(y);cells[at/2u][at%2u]=m+1u;return;}
 put_cell(u32(y)*data[2]*data[2]+col+1u,m);let masks=data[data[9]+m];
 for(var map=0u;map<6u;map++){if((masks&(1u<<map))!=0u){let key=0x80000000u|(map*data[2]*data[2]+col+1u);let old=get_cell(key);if(old==NONE||bitcast<i32>(old)<p.y){put_cell(key,bitcast<u32>(p.y));}}}
}
fn height(p:vec3<i32>,map:u32)->i32{
 let col=column(p);if(col==NONE){return bitcast<i32>(data[3]);}var y=bitcast<i32>(data[32u+col*16u+8u+map]);
 let top=get_cell(0x80000000u|(map*data[2]*data[2]+col+1u));if(top!=NONE){y=max(y,bitcast<i32>(top)+1);}
 while(y>bitcast<i32>(data[3])){let m=material(vec3<i32>(p.x,y-1,p.z));if(m==NONE){return y;}if((data[data[9]+m]&(1u<<map))!=0u){break;}y--;}
 return y;
}
fn predicate(at:u32,len:u32,p:vec3<i32>)->bool{
 var stack:array<u32,64>;var depth=0u;
 for(var i=0u;i<len;i++){
  let op=data[12]+at+i*8u;let kind=data[op];var result=1u;
  if(kind==5u){result=0u;}else if(kind==1u){
   let m=material(p+vec3<i32>(bitcast<i32>(data[op+1u]),bitcast<i32>(data[op+2u]),bitcast<i32>(data[op+3u])));
   if(m==NONE){result=2u;}else{let start=data[12]+data[op+4u];let len=data[op+5u];var lo=0u;var hi=len;while(lo<hi){let mid=(lo+hi)/2u;if(data[start+mid]<m){lo=mid+1u;}else{hi=mid;}}result=0u;if(lo<len&&data[start+lo]==m){result=1u;}}
  }else if(kind==2u){let a=stack[depth-1u];stack[depth-1u]=select(1u-a,2u,a==2u);continue;}
  else if(kind==3u||kind==4u){depth--;let a=stack[depth-1u];let b=stack[depth];result=2u;if(kind==3u){if(a==0u||b==0u){result=0u;}else if(a==1u&&b==1u){result=1u;}}else{if(a==1u||b==1u){result=1u;}else if(a==0u&&b==0u){result=0u;}}stack[depth-1u]=result;continue;}
  stack[depth]=result;depth++;
 }return depth==1u&&stack[0]==1u;
}
fn possible(at:u32,len:u32,p:vec3<i32>)->bool{
 var stack:array<u32,64>;var depth=0u;
 for(var i=0u;i<len;i++){
  let op=data[12]+at+i*8u;let kind=data[op];var result=2u;
  if(kind==5u){result=1u;}else if(kind==1u){
   let m=material(p+vec3<i32>(bitcast<i32>(data[op+1u]),bitcast<i32>(data[op+2u]),bitcast<i32>(data[op+3u])));
   if(m==NONE){result=3u;}else{let start=data[12]+data[op+4u];let len=data[op+5u];var lo=0u;var hi=len;while(lo<hi){let mid=(lo+hi)/2u;if(data[start+mid]<m){lo=mid+1u;}else{hi=mid;}}result=1u;if(lo<len&&data[start+lo]==m){result=2u;}result|=data[op+6u]*2u|data[op+7u];}
  }else if(kind==2u){let a=stack[depth-1u];stack[depth-1u]=((a&1u)<<1u)|((a&2u)>>1u);continue;}
  else if(kind==3u||kind==4u){depth--;let a=stack[depth-1u];let b=stack[depth];if(kind==3u){result=((a|b)&1u)|((a&b)&2u);}else{result=((a&b)&1u)|((a|b)&2u);}stack[depth-1u]=result;continue;}
  stack[depth]=result;depth++;
 }return depth==1u&&(stack[0]&2u)!=0u;
}
fn biome(p:vec3<i32>)->bool{
 let col=column(p);if(col==NONE){return false;}let d=32u+col*16u;var allowed=data[d+5u];let y=p.y-bitcast<i32>(data[3]);
 if(data[17]!=0u&&p.y<bitcast<i32>(data[d+2u])-12&&y>=0&&y<i32(data[4])){let qy=u32(p.y-bitcast<i32>(data[20]))/4u;let qx=(col%data[2])/4u;let qz=(col/data[2])/4u;let v=data[data[19]+(qy*data[21]+qz)*data[21]+qx];if(v!=NONE){allowed=v;}}
 return allowed!=0u;
}
@compute @workgroup_size(64)
fn prepare(@builtin(global_invocation_id) gid:vec3<u32>){
 owner=gid.y;let n=data[24]*data[25];if(owner>=data[6]||gid.x>=n){return;}failed=false;
 let a=anchors[owner];let outer=gid.x/data[25];let inner=gid.x%data[25];let increment=vec2<u32>(0x7f4a7c15u,0x9e3779b9u);
 var parent=add64(a.zw,mul64(increment,vec2<u32>(outer,0u)));var child=next64(&parent);child=add64(child,mul64(increment,vec2<u32>(inner,0u)));var rng=next64(&child);
 let x=bitcast<i32>(a.x)*16+below(&rng,16);let z=bitcast<i32>(a.y)*16+below(&rng,16);let y=integer(data[26],&rng);let p=vec3<i32>(x,y,z);
 let feature=data[12]+data[23];let yes=biome(p)&&possible(data[27],data[28],p)&&possible(data[feature],data[feature+1u],p);
 candidates[owner*n+gid.x]=vec4<u32>(bitcast<vec3<u32>>(p),select(u32(yes),2u,failed));
 if(column_mode()){
  if(failed){atomicStore(&column_lists[list_base()],1u);return;}
  if(yes){let col=u32(z-bitcast<i32>(a.y)*16)*16u+u32(x-bitcast<i32>(a.x)*16);let start=list_base()+1u+col*(1u+data[30]);
   let index=atomicAdd(&column_lists[start],1u);if(index<data[30]){atomicStore(&column_lists[start+1u+index],gid.x);}else{atomicStore(&column_lists[list_base()],1u);}
  }
 }
}
fn plant(p:vec3<i32>,rng:ptr<function,vec2<u32>>){
 if(p.y<bitcast<i32>(data[3])||p.y>=bitcast<i32>(data[3])+i32(data[4])){return;}
 let col=column(p);if(col==NONE){return;}let d=32u+col*16u;
 if(p.y==bitcast<i32>(data[d+2u])&&data[d+4u]!=0u){return;}
 if(data[22]!=0u){
  let f=data[12]+data[23];if(!predicate(data[f],data[f+1u],p)){return;}
  for(var i=0u;i<data[f+2u];i++){
   let s=f+3u+i*3u;if(!predicate(data[s+1u],data[s+2u],p)){continue;}
   if(column_mode()){let m=data[s];candidates[candidate_index].w=m+3u;write(p,m);return;}
   if(used>=data[5]){failed=true;return;}
   let m=data[s];let o=out_base+4u+used*5u;output[o]=bitcast<u32>(p.x);output[o+1u]=bitcast<u32>(p.y);output[o+2u]=bitcast<u32>(p.z);output[o+3u]=m;output[o+4u]=0u;used++;write(p,m);return;
  }return;
 }
 let band=select(1u,0u,data[d+3u]!=0u);let start=data[13u+band*2u];let n=data[14u+band*2u];let u=shr64(next64(rng),11u);
 for(var i=0u;i<n;i++){
  let s=start+i*5u;if(!less64(u,vec2<u32>(data[s],data[s+1u]))){continue;}
  let soil=material(p+vec3<i32>(0,-1,0));if(soil==NONE||(data[data[8]+soil]&select(1u,2u,data[s+4u]!=0u))==0u){return;}
  if(used>=data[5]){failed=true;return;}
  let m=data[s+2u];let upper=data[s+3u];let o=out_base+4u+used*5u;output[o]=bitcast<u32>(p.x);output[o+1u]=bitcast<u32>(p.y);output[o+2u]=bitcast<u32>(p.z);output[o+3u]=m;output[o+4u]=upper;used++;
  if(material(p)==0u){if(upper!=0u){if(p.y+1>=bitcast<i32>(data[3])+i32(data[4])||material(p+vec3<i32>(0,1,0))!=0u){return;}write(p+vec3<i32>(0,1,0),upper);}write(p,m);}return;
 }
}
@compute @workgroup_size(64)
fn columns(@builtin(global_invocation_id) gid:vec3<u32>){
 owner=gid.y;local_column=gid.x;failed=false;if(owner>=data[6]||local_column>=256u){return;}
 if(atomicLoad(&column_lists[list_base()])!=0u){return;}
 let start=list_base()+1u+local_column*(1u+data[30]);let n=atomicLoad(&column_lists[start]);
 if(n>data[30]||n>128u){atomicStore(&column_lists[list_base()],1u);return;}
 var order:array<u32,128>;
 for(var i=0u;i<n;i++){let id=atomicLoad(&column_lists[start+1u+i]);var j=i;while(j>0u&&order[j-1u]>id){order[j]=order[j-1u];j--;}order[j]=id;}
 var rng=vec2<u32>(0u);
 for(var i=0u;i<n;i++){
  candidate_index=owner*data[24]*data[25]+order[i];let p=bitcast<vec3<i32>>(candidates[candidate_index].xyz);candidates[candidate_index].w=0u;
  if(predicate(data[27],data[28],p)){plant(p,&rng);}if(failed){atomicStore(&column_lists[list_base()],1u);return;}
 }
}
@compute @workgroup_size(32)
fn main(@builtin(global_invocation_id) gid:vec3<u32>){
 owner=gid.x;if(owner>=data[6]){return;}out_base=owner*(4u+data[5]*5u);used=0u;failed=false;
 let a=anchors[owner];let origin=vec3<i32>(bitcast<i32>(a.x)*16,bitcast<i32>(data[3]),bitcast<i32>(a.y)*16);
 if(data[24]>0u){
  let n=data[24]*data[25];var rng=vec2<u32>(0u);
  if(column_mode()&&atomicLoad(&column_lists[list_base()])!=0u){output[out_base]=0u;output[out_base+1u]=1u;return;}
  for(var i=0u;i<n;i++){
   let c=candidates[owner*n+i];if(c.w==0u){continue;}if(c.w==2u){failed=true;break;}
   let p=bitcast<vec3<i32>>(c.xyz);
   if(column_mode()){
    if(c.w<3u||used>=data[5]){failed=true;break;}
    let o=out_base+4u+used*5u;output[o]=c.x;output[o+1u]=c.y;output[o+2u]=c.z;output[o+3u]=c.w-3u;output[o+4u]=0u;used++;
   }else{if(predicate(data[27],data[28],p)){plant(p,&rng);}}
   if(failed){break;}
  }
  output[out_base]=select(used,0u,failed);output[out_base+1u]=u32(failed);output[out_base+2u]=n;output[out_base+3u]=0u;return;
 }
 var rng=a.zw;var pc=0u;var depth=0u;var attempts=0u;
 var frame_pc:array<u32,8>;var remaining:array<u32,8>;var frame_rng:array<vec2<u32>,8>;
 var draws:array<vec3<i32>,24>;var cache:array<vec4<i32>,24>;
 for(var budget=0u;budget<262144u;budget++){
  var back=false;
  if(pc==data[11]){
   attempts++;var p=origin;var valid=true;
   for(var i=0u;i<data[11];i++){
    let o=data[10]+i*8u;let kind=data[o];
    if(kind==2u||kind==3u){p+=draws[i];continue;}if(kind==10u){p.y=draws[i].y;continue;}if(kind<6u){continue;}
    if(data[o+7u]!=0u&&cache[i].w!=0){valid=cache[i].w==1;p=cache[i].xyz;if(!valid){break;}continue;}
    if(kind==6u){p.y=height(p,data[o+1u]);}
    else if(kind==7u){let col=column(p);if(col==NONE){valid=false;}else{let d=32u+col*16u;var allowed=data[d+5u];let y=p.y-bitcast<i32>(data[3]);if(data[17]!=0u&&p.y<bitcast<i32>(data[d+2u])-12&&y>=0&&y<i32(data[4])){let qy=u32(p.y-bitcast<i32>(data[20]))/4u;let qx=(col%data[2])/4u;let qz=(col/data[2])/4u;let v=data[data[19]+(qy*data[21]+qz)*data[21]+qx];if(v!=NONE){allowed=v;}}valid=allowed!=0u;}}
    else if(kind==8u){valid=predicate(data[o+1u],data[o+2u],p);}
    else if(kind==9u){valid=height(p,1u)-height(p,3u)<=bitcast<i32>(data[o+1u]);}
    if(data[o+7u]!=0u){cache[i]=vec4<i32>(p,select(2,1,valid));}if(!valid){break;}
   }
   if(valid&&!failed){plant(p,&rng);}back=true;
  }else{
   let o=data[10]+pc*8u;let kind=data[o];
   if(kind==1u){let count=max(0,integer(data[o+1u],&rng));if(count==0){back=true;}else{frame_pc[depth]=pc;remaining[depth]=u32(count-1);let child=next64(&rng);frame_rng[depth]=rng;depth++;rng=child;for(var i=pc+1u;i<data[11];i++){cache[i].w=0;}pc++;}}
   else if(kind==2u){let x=below(&rng,16);draws[pc]=vec3<i32>(x,0,below(&rng,16));pc++;}
   else if(kind==3u){let x=integer(data[o+1u],&rng);let y=integer(data[o+2u],&rng);let z=integer(data[o+3u],&rng);draws[pc]=vec3<i32>(x,y,z);pc++;}
   else if(kind==10u){draws[pc]=vec3<i32>(0,integer(data[o+1u],&rng),0);pc++;}
   else if(kind==4u){back=!less64(shr64(next64(&rng),11u),vec2<u32>(data[o+1u],data[o+2u]));pc++;}
   else if(kind==5u){let u=shr64(next64(&rng),11u);back=less64(u,vec2<u32>(data[o+1u],data[o+2u]))||!less64(u,vec2<u32>(data[o+3u],data[o+4u]));pc++;}
   else{pc++;}
  }
  if(failed){break;}
  if(back){var found=false;while(depth>0u){let f=depth-1u;if(remaining[f]>0u){remaining[f]--;var parent=frame_rng[f];rng=next64(&parent);frame_rng[f]=parent;pc=frame_pc[f]+1u;for(var i=pc;i<data[11];i++){cache[i].w=0;}found=true;break;}depth--;}
   if(!found){output[out_base]=used;output[out_base+1u]=0u;output[out_base+2u]=attempts;output[out_base+3u]=0u;return;}
  }
 }
 output[out_base]=0u;output[out_base+1u]=1u;output[out_base+2u]=attempts;output[out_base+3u]=0u;
}

var<workgroup> gather_counts:array<u32,64>;
var<workgroup> gather_errors:array<u32,64>;
var<workgroup> gather_failed:u32;
@compute @workgroup_size(64)
fn gather(@builtin(workgroup_id) group:vec3<u32>,@builtin(local_invocation_index) lane:u32){
 owner=group.x;
 let n=data[24]*data[25];let stride=(n+63u)/64u;let start=min(lane*stride,n);let end=min(start+stride,n);
 var count=0u;var error=0u;
 for(var i=start;i<end;i++){let flag=candidates[owner*n+i].w;count+=u32(flag>=3u);error|=u32(flag==1u||flag==2u);}
 gather_counts[lane]=count;gather_errors[lane]=error;workgroupBarrier();
 var offset=0u;for(var i=0u;i<lane;i++){offset+=gather_counts[i];}
 let base=owner*(4u+data[5]*5u);
 if(lane==0u){
  var total=0u;var err=atomicLoad(&column_lists[list_base()]);
  for(var i=0u;i<64u;i++){total+=gather_counts[i];err|=gather_errors[i];}
  gather_failed=u32(err!=0u||total>data[5]);output[base]=select(total,0u,gather_failed!=0u);output[base+1u]=gather_failed;output[base+2u]=n;output[base+3u]=0u;
 }
 workgroupBarrier();if(gather_failed!=0u){return;}
 for(var i=start;i<end;i++){
  let c=candidates[owner*n+i];if(c.w<3u){continue;}let o=base+4u+offset*5u;
  output[o]=c.x;output[o+1u]=c.y;output[o+2u]=c.z;output[o+3u]=c.w-3u;output[o+4u]=0u;offset++;
 }
}
