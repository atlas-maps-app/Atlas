import zlib, struct
def read_png(path):
    d=open(path,'rb').read(); assert d[:8]==b'\x89PNG\r\n\x1a\n'
    pos=8; idat=b''; w=h=0
    while pos<len(d):
        ln,=struct.unpack('>I',d[pos:pos+4]); t=d[pos+4:pos+8]; c=d[pos+8:pos+8+ln]; pos+=12+ln
        if t==b'IHDR': w,h,bd,ct,_,_,il=struct.unpack('>IIBBBBB',c); assert bd==8 and il==0
        elif t==b'IDAT': idat+=c
    bpp={6:4,2:3,0:1,4:2}[ct]; raw=zlib.decompress(idat); stride=w*bpp
    out=bytearray(h*stride); prev=bytearray(stride); p=0
    for y in range(h):
        f=raw[p]; p+=1; line=bytearray(raw[p:p+stride]); p+=stride
        for i in range(stride):
            a=line[i-bpp] if i>=bpp else 0; b=prev[i]; c=prev[i-bpp] if i>=bpp else 0
            if f==1: line[i]=(line[i]+a)&255
            elif f==2: line[i]=(line[i]+b)&255
            elif f==3: line[i]=(line[i]+((a+b)>>1))&255
            elif f==4:
                pa=abs(b-c); pb=abs(a-c); pc=abs(a+b-2*c)
                pr=a if pa<=pb and pa<=pc else (b if pb<=pc else c)
                line[i]=(line[i]+pr)&255
        out[y*stride:(y+1)*stride]=line; prev=line
    return w,h,bpp,out
