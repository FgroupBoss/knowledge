const fs=require('fs'),vm=require('vm');const c={};vm.createContext(c);vm.runInContext(fs.readFileSync('work/md5.js','utf8'),c); process.stdout.write(c.md5(c.md5('Zt@2026Admin')));
