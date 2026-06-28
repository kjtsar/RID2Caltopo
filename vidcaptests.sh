#!/bin/sh -v
cd /Users/kjt/Projects/RID2Caltopo
cmake -S tools/anomaly_test -B tools/anomaly_test/build
cmake --build tools/anomaly_test/build
./tools/anomaly_test/build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/PowerHouse1.mp4 -p bh -a 6 -t 2.8 -m 2 -s 0.6

