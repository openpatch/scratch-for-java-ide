package org.openpatch.scratch4j.sound;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrophoneRecorderTest {

  @Test
  void offersTheSoundServersMicrophonesButNotItsMonitors() {
    String output = """
        Source #64
        \tState: RUNNING
        \tName: alsa_output.pci-0000_c4_00.1.HiFi__HDMI4__sink.monitor
        \tDescription: Monitor of HDMI 4 Output
        \tDriver: PipeWire
        Source #72
        \tState: RUNNING
        \tName: alsa_input.usb-Samson_Q2U_Microphone-00.analog-stereo
        \tDescription: Samson Q2U Microphone Analog Stereo
        \tDriver: PipeWire
        """;
    assertThat(MicrophoneRecorder.parseSources(output)).containsExactly(
        new MicrophoneRecorder.Device("pulse:alsa_input.usb-Samson_Q2U_Microphone-00.analog-stereo",
            "Samson Q2U Microphone Analog Stereo"));
  }
}
