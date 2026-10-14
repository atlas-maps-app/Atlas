import sys,re,collections
defs=collections.Counter(); ex={}
pat=re.compile(r'<filter [^>]*>.*?</filter>',re.S)
for f in sys.argv[1:]:
    s=open(f).read()
    for m in pat.finditer(s):
        t=m.group(0)
        key=re.sub(r'id="[^"]*"','id=?',t)
        key=re.sub(r'(seed|scale|baseFrequency|stdDeviation)="[^"]*"',r'\1=?',key)
        key=re.sub(r'values="[^"]*"', lambda mm: 'values=?' , key)
        defs[key]+=1; ex.setdefault(key,(f.split('/')[-1],t))
for k,n in defs.most_common():
    print(n, ex[k][0]); print('   ', ex[k][1][:900]); print()
