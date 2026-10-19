// Complete, registered column-independent vine programs. Prior feature writes
// remain immutable across columns; each column commits its own candidates in order.
@group(0) @binding(0) var<storage,read> data:array<u32>;
@group(0) @binding(1) var<storage,read> anchor_data:array<u32>;
@group(0) @binding(2) var<storage,read_write> cells:array<u32>;
@group(0) @binding(3) var<storage,read_write> output:array<u32>;
@group(0) @binding(4) var<storage,read_write> candidates:array<vec4<u32>>;
@group(0) @binding(5) var<storage,read_write> column_lists:array<atomic<u32>>;
var<private> owner:u32;
var<private> local_column:u32;
var<private> candidate_index:u32;
var<private> failed:bool;
const NONE:u32=0xffffffffu;
const UNKNOWN_MATERIAL:u32=1u;
const QUEUE_FULL:u32=2u;
const OUTPUT_FULL:u32=4u;
const INCOMPLETE:u32=8u;
fn anchor(i:u32)->vec4<u32>{let at=4u+i*8u;return vec4<u32>(anchor_data[at],anchor_data[at+1u],anchor_data[at+2u],anchor_data[at+3u]);}
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

fn below(r:ptr<function,vec2<u32>>,n:i32)->i32{
 if(n<=1){return 0;}let a=next64(r);let d=u32(n);var rem=(a.y>>16u)%d;
 rem=(rem*65536u+(a.y&65535u))%d;rem=(rem*65536u+(a.x>>16u))%d;return i32((rem*65536u+(a.x&65535u))%d);
}

fn column(p:vec3<i32>)->u32{
 let x=p.x-bitcast<i32>(data[0]);let z=p.z-bitcast<i32>(data[1]);let w=i32(data[2]);
 if(x<0||z<0||x>=w||z>=w){failed=true;return NONE;}return u32(z*w+x);
}

fn integer(at:u32,r:ptr<function,vec2<u32>>)->i32{let i=data[12]+at;let a=bitcast<i32>(data[i+1u]);if(data[i]==0u){return a;}return a+below(r,bitcast<i32>(data[i+2u])-a+1);}
fn material(p:vec3<i32>)->u32{
 let y=p.y-bitcast<i32>(data[3]);if(y<0||y>=i32(data[4])){return 0u;}
 let col=column(p);if(col==NONE){return NONE;}let key=u32(y)*data[2]*data[2]+col+1u;
 let a=anchor(owner);let lx=p.x-bitcast<i32>(a.x)*16;let lz=p.z-bitcast<i32>(a.y)*16;
 if(lx>=0&&lz>=0&&lx<16&&lz<16&&u32(lz*16+lx)==local_column){let live=cells[(owner*256u+local_column)*data[4]+u32(y)];if(live!=0u){return live-1u;}}
 let desc=4u+owner*8u;let prior=anchor_data[desc+4u];let plen=anchor_data[desc+5u];var lo=0u;var hi=plen;
 while(lo<hi){let mid=(lo+hi)/2u;if(anchor_data[prior+mid*2u]<key){lo=mid+1u;}else{hi=mid;}}
 if(lo<plen&&anchor_data[prior+lo*2u]==key){return anchor_data[prior+lo*2u+1u];}
 let d=32u+col*8u;let start=data[d];let len=data[d+1u];lo=0u;hi=len;
 while(lo<hi){let mid=(lo+hi)/2u;if((data[start+mid]>>16u)>u32(y)){lo=mid+1u;}else{hi=mid;}}
 if(lo==len){failed=true;return NONE;}return data[start+lo]&65535u;
}
fn write(p:vec3<i32>,m:u32){let y=p.y-bitcast<i32>(data[3]);cells[(owner*256u+local_column)*data[4]+u32(y)]=m+1u;}


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
 let col=column(p);if(col==NONE){return false;}let d=32u+col*8u;var allowed=data[d+5u];let y=p.y-bitcast<i32>(data[3]);
 if(data[17]!=0u&&p.y<bitcast<i32>(data[d+2u])-12&&y>=0&&y<i32(data[4])){let qy=u32(p.y-bitcast<i32>(data[20]))/4u;let qx=(col%data[2])/4u;let qz=(col/data[2])/4u;let v=data[data[19]+(qy*data[21]+qz)*data[21]+qx];if(v!=NONE){allowed=v;}}
 return allowed!=0u;
}

@compute @workgroup_size(64)
fn prepare(@builtin(global_invocation_id) gid:vec3<u32>){
 owner=gid.y;let n=data[24]*data[25];if(owner>=anchor_data[0]||gid.x>=n){return;}failed=false;
 let a=anchor(owner);let outer=gid.x/data[25];let inner=gid.x%data[25];let increment=vec2<u32>(0x7f4a7c15u,0x9e3779b9u);
 var parent=add64(a.zw,mul64(increment,vec2<u32>(outer,0u)));var child=next64(&parent);child=add64(child,mul64(increment,vec2<u32>(inner,0u)));var rng=next64(&child);
 let x=bitcast<i32>(a.x)*16+below(&rng,16);let z=bitcast<i32>(a.y)*16+below(&rng,16);let y=integer(data[26],&rng);let p=vec3<i32>(x,y,z);
 let feature=data[12]+data[23];let yes=biome(p)&&possible(data[27],data[28],p)&&possible(data[feature],data[feature+1u],p);
 candidates[owner*n+gid.x]=vec4<u32>(bitcast<vec3<u32>>(p),select(u32(yes),2u,failed));
 {
  if(failed){atomicOr(&column_lists[list_base()],UNKNOWN_MATERIAL);return;}
  if(yes){let col=u32(z-bitcast<i32>(a.y)*16)*16u+u32(x-bitcast<i32>(a.x)*16);let start=list_base()+1u+col*(1u+data[30]);
   let index=atomicAdd(&column_lists[start],1u);if(index<data[30]){atomicStore(&column_lists[start+1u+index],gid.x);}else{atomicOr(&column_lists[list_base()],QUEUE_FULL);}
  }
 }
}

fn vine(p:vec3<i32>){
 if(p.y<bitcast<i32>(data[3])||p.y>=bitcast<i32>(data[3])+i32(data[4])){return;}
 let col=column(p);if(col==NONE){return;}let d=32u+col*8u;
 if(p.y==bitcast<i32>(data[d+2u])&&data[d+4u]!=0u){return;}
 let f=data[12]+data[23];if(!predicate(data[f],data[f+1u],p)){return;}
 for(var i=0u;i<data[f+2u];i++){let s=f+3u+i*3u;if(predicate(data[s+1u],data[s+2u],p)){let m=data[s];candidates[candidate_index].w=m+3u;write(p,m);return;}}
}

@compute @workgroup_size(64)
fn columns(@builtin(global_invocation_id) gid:vec3<u32>){
 owner=gid.y;local_column=gid.x;failed=false;if(owner>=anchor_data[0]||local_column>=256u){return;}
 if(atomicLoad(&column_lists[list_base()])!=0u){return;}
 let start=list_base()+1u+local_column*(1u+data[30]);let n=atomicLoad(&column_lists[start]);
 if(n>data[30]||n>128u){atomicOr(&column_lists[list_base()],QUEUE_FULL);return;}
 var order:array<u32,128>;
 for(var i=0u;i<n;i++){let id=atomicLoad(&column_lists[start+1u+i]);var j=i;while(j>0u&&order[j-1u]>id){order[j]=order[j-1u];j--;}order[j]=id;}
 
 for(var i=0u;i<n;i++){
  candidate_index=owner*data[24]*data[25]+order[i];let p=bitcast<vec3<i32>>(candidates[candidate_index].xyz);candidates[candidate_index].w=0u;
  if(predicate(data[27],data[28],p)){vine(p);}if(failed){atomicOr(&column_lists[list_base()],UNKNOWN_MATERIAL);return;}
 }
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
  var pending=0u;for(var i=0u;i<64u;i++){total+=gather_counts[i];pending|=gather_errors[i];}
  if(err==0u&&pending!=0u){err|=INCOMPLETE;}if(total>data[5]){err|=OUTPUT_FULL;}
  gather_failed=err;output[base]=total;output[base+1u]=gather_failed;output[base+2u]=n;output[base+3u]=0u;
 }
 workgroupBarrier();if(gather_failed!=0u){return;}
 for(var i=start;i<end;i++){
  let c=candidates[owner*n+i];if(c.w<3u){continue;}let o=base+4u+offset*5u;
  output[o]=c.x;output[o+1u]=c.y;output[o+2u]=c.z;output[o+3u]=c.w-3u;output[o+4u]=0u;offset++;
 }
}
