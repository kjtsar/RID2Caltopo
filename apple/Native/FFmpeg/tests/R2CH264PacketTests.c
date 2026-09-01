#include "../R2CH264Packet.h"
#include "../../../../native/R2CLocalPlaybackCadence.h"

#include <assert.h>

int main(void) {
    const uint8_t avccSei[] = {0, 0, 0, 3, 0x06, 0x05, 0x80};
    R2CH264PacketContents contents = R2CH264InspectPacket(avccSei, sizeof(avccSei), 4);
    assert(R2CH264PacketIsSeiOnly(contents));

    const uint8_t avccPicture[] = {
        0, 0, 0, 2, 0x06, 0x80,
        0, 0, 0, 3, 0x65, 0x01, 0x02,
    };
    contents = R2CH264InspectPacket(avccPicture, sizeof(avccPicture), 4);
    assert(contents.hasSei && contents.hasPicture);
    assert(!R2CH264PacketIsSeiOnly(contents));

    const uint8_t annexBSei[] = {0, 0, 0, 1, 0x06, 0x05, 0x80};
    contents = R2CH264InspectPacket(annexBSei, sizeof(annexBSei), 4);
    assert(R2CH264PacketIsSeiOnly(contents));

    const uint8_t parameterSet[] = {0, 0, 0, 2, 0x67, 0x64};
    contents = R2CH264InspectPacket(parameterSet, sizeof(parameterSet), 4);
    assert(contents.hasOtherNal && !R2CH264PacketIsSeiOnly(contents));

    const uint8_t malformed[] = {0, 0, 0, 12, 0x06};
    contents = R2CH264InspectPacket(malformed, sizeof(malformed), 4);
    assert(!contents.recognized && !R2CH264PacketIsSeiOnly(contents));

    R2CLocalPlaybackCadence cadence;
    R2CLocalPlaybackCadenceInit(&cadence);
    assert(R2CLocalPlaybackCadenceNextIntervalUs(&cadence, 1000000) == 0);
    assert(R2CLocalPlaybackCadenceNextIntervalUs(&cadence, 1033000) == 33333);
    assert(R2CLocalPlaybackCadenceNextIntervalUs(&cadence, 1067000) == 33333);
    assert(R2CLocalPlaybackCadenceNextIntervalUs(&cadence, 1087000) == 29000);
    assert(R2CLocalPlaybackCadenceNextIntervalUs(&cadence, 1137000) == 34250);
    assert(R2CLocalPlaybackCadenceNextIntervalUs(&cadence, 1437000) == 300000);
    return 0;
}
