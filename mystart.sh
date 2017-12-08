!#/bin/bash
echo $KARAF_ROOT
cd /root/onos
source /etc/profile
source ./tools/dev/bash_profile
cd ./tools/build/
rm -rf /home/lhf/git/onos/buck-out/gen/tools/package/onos-package/onos.tar.gz
./onos-buck build onos --show-output

cp /root/onos/buck-out/gen/tools/package/onos-package/onos.tar.gz /home/lhf/git/onos/buck-out/gen/tools/package/onos-package/
./onos-buck run onos-local -- clean debug
