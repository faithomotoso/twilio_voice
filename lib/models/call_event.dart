part of twilio_voice;

enum CallEvent {
  ringing,
  connected,
  callEnded,
  unhold,
  hold,
  unmute,
  mute,
  speakerOn,
  speakerOff,
  log,
  declined,
  answer,
  missedCall,
  returningCall,
  callFailure
}

class CallEventM {
  final CallEvent callEvent;
  final String? message;

  CallEventM({required this.callEvent, this.message});

  @override
  String toString() {
    return "$callEvent -> $message";
  }
}
