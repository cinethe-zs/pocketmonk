import 'package:flutter/material.dart';
import 'package:speech_to_text/speech_to_text.dart';

import '../theme/app_theme.dart';

class ChatInputBar extends StatefulWidget {
  final bool isGenerating;
  final void Function(String) onSend;
  final VoidCallback onStop;

  const ChatInputBar({
    super.key,
    required this.isGenerating,
    required this.onSend,
    required this.onStop,
  });

  @override
  State<ChatInputBar> createState() => _ChatInputBarState();
}

class _ChatInputBarState extends State<ChatInputBar> {
  final _controller  = TextEditingController();
  final _focusNode   = FocusNode();
  final _stt         = SpeechToText();
  bool  _hasText     = false;
  bool  _sttReady    = false;
  bool  _isListening = false;

  @override
  void initState() {
    super.initState();
    _controller.addListener(() {
      final hasText = _controller.text.trim().isNotEmpty;
      if (hasText != _hasText) setState(() => _hasText = hasText);
    });
    _initStt();
  }

  Future<void> _initStt() async {
    final available = await _stt.initialize();
    if (mounted) setState(() => _sttReady = available);
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    _stt.stop();
    super.dispose();
  }

  void _submit() {
    final text = _controller.text.trim();
    if (text.isEmpty || widget.isGenerating) return;
    widget.onSend(text);
    _controller.clear();
    _focusNode.requestFocus();
  }

  Future<void> _toggleListening() async {
    if (_isListening) {
      await _stt.stop();
      setState(() => _isListening = false);
      return;
    }

    setState(() => _isListening = true);
    await _stt.listen(
      onResult: (result) {
        _controller.text = result.recognizedWords;
        _controller.selection = TextSelection.fromPosition(
          TextPosition(offset: _controller.text.length),
        );
        if (result.finalResult) {
          setState(() => _isListening = false);
        }
      },
      listenFor:      const Duration(seconds: 30),
      pauseFor:       const Duration(seconds: 3),
      localeId:       'en_US',
      listenOptions:  SpeechListenOptions(partialResults: true),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.fromLTRB(
        12, 10, 12,
        10 + MediaQuery.of(context).viewPadding.bottom,
      ),
      decoration: const BoxDecoration(
        color: AppTheme.background,
        border: Border(top: BorderSide(color: AppTheme.border)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          // Mic button — shown when STT is ready and not currently generating
          if (_sttReady && !widget.isGenerating)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: _MicButton(
                isListening: _isListening,
                onTap:       _toggleListening,
              ),
            ),

          // Text field
          Expanded(
            child: TextField(
              controller:   _controller,
              focusNode:    _focusNode,
              enabled:      !widget.isGenerating,
              maxLines:     6,
              minLines:     1,
              textInputAction: TextInputAction.newline,
              keyboardType:    TextInputType.multiline,
              style: const TextStyle(
                color:    AppTheme.textPrimary,
                fontSize: 15,
                height:   1.5,
              ),
              decoration: InputDecoration(
                hintText:  _isListening ? 'Listening…' : 'Ask anything…',
                hintStyle: TextStyle(
                    color: _isListening ? AppTheme.accent : AppTheme.textMuted),
                border:         InputBorder.none,
                enabledBorder:  InputBorder.none,
                focusedBorder:  InputBorder.none,
                filled:         true,
                fillColor:      AppTheme.surfaceRaised,
                contentPadding: const EdgeInsets.symmetric(
                    horizontal: 16, vertical: 12),
              ),
            ),
          ),

          const SizedBox(width: 8),

          // Send / Stop button
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 180),
            child: widget.isGenerating
                ? _StopButton(onStop: widget.onStop)
                : _SendButton(
                    enabled: _hasText,
                    onSend:  _submit,
                  ),
          ),
        ],
      ),
    );
  }
}

// ── Mic button ────────────────────────────────────────────────────────────────

class _MicButton extends StatelessWidget {
  final bool isListening;
  final VoidCallback onTap;
  const _MicButton({required this.isListening, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      width:  44,
      height: 44,
      decoration: BoxDecoration(
        color: isListening
            ? AppTheme.accent.withAlpha(30)
            : AppTheme.surfaceRaised,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: isListening ? AppTheme.accent : AppTheme.border,
        ),
      ),
      child: IconButton(
        onPressed: onTap,
        icon: Icon(
          isListening ? Icons.mic_rounded : Icons.mic_none_rounded,
        ),
        iconSize: 20,
        color:   isListening ? AppTheme.accent : AppTheme.textMuted,
        padding: EdgeInsets.zero,
      ),
    );
  }
}

// ── Send button ───────────────────────────────────────────────────────────────

class _SendButton extends StatelessWidget {
  final bool enabled;
  final VoidCallback onSend;
  const _SendButton({required this.enabled, required this.onSend});

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      width:  44,
      height: 44,
      decoration: BoxDecoration(
        color:        enabled ? AppTheme.accent : AppTheme.surfaceRaised,
        borderRadius: BorderRadius.circular(12),
        border:       Border.all(color: enabled ? AppTheme.accent : AppTheme.border),
      ),
      child: IconButton(
        onPressed: enabled ? onSend : null,
        icon: const Icon(Icons.arrow_upward_rounded),
        iconSize: 20,
        color: enabled ? Colors.white : AppTheme.textMuted,
        padding: EdgeInsets.zero,
      ),
    );
  }
}

// ── Stop button ───────────────────────────────────────────────────────────────

class _StopButton extends StatelessWidget {
  final VoidCallback onStop;
  const _StopButton({required this.onStop});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width:  44,
      height: 44,
      child: Container(
        decoration: BoxDecoration(
          color:        AppTheme.surfaceRaised,
          borderRadius: BorderRadius.circular(12),
          border:       Border.all(color: AppTheme.border),
        ),
        child: IconButton(
          onPressed: onStop,
          icon: const Icon(Icons.stop_rounded),
          iconSize: 20,
          color: AppTheme.error,
          padding: EdgeInsets.zero,
        ),
      ),
    );
  }
}
