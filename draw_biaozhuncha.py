# -*- coding: utf-8 -*-

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from pandas import read_csv
from pandas import DataFrame
import matplotlib

fesm_data = read_csv('/home/zengxiaosen/BandWidthStandardDeviation_FESM.csv',header=None)
pllb_data = read_csv('/home/zengxiaosen/BandWidthStandardDeviation_PLLB.csv',header=None)

#转换成mbps
fesm_data = fesm_data / 1000
pllb_data = pllb_data / 1000

index1 = []
for i in range(len(fesm_data)):
    index1.append(i * 3 +1)

index2 = []
for i in range(len(pllb_data)):
    index2.append(i * 3 +1)
    
#设置matplotlib可以显示中文
matplotlib.rcParams['font.family']='SimHei'

plt.xlabel('time：second')
plt.ylabel('BandWidthStandardDeviation:mbps')
plt.title('PerLinkBandWidthStandardDeviation')
plt.plot(index1,fesm_data,index2,pllb_data)
plt.savefig('fesm.png',dpi=600)
plt.show()
plt.close()
