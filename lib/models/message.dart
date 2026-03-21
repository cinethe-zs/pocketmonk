import 'package:uuid/uuid.dart';

enum MessageRole { user, assistant, system }

enum MessageStatus { done, streaming, error }

class Message {
  final String id;
  final MessageRole role;
  String content;
  MessageStatus status;
  final DateTime createdAt;
  bool starred;
  bool isSummary;   // true = compressed context card, not a real turn

  Message({
    String? id,
    required this.role,
    required this.content,
    this.status = MessageStatus.done,
    this.starred = false,
    this.isSummary = false,
    DateTime? createdAt,
  })  : id = id ?? const Uuid().v4(),
        createdAt = createdAt ?? DateTime.now();

  bool get isUser      => role == MessageRole.user;
  bool get isAssistant => role == MessageRole.assistant;
  bool get isStreaming => status == MessageStatus.streaming;
  bool get isError     => status == MessageStatus.error;

  Message copyWith({String? content, MessageStatus? status, bool? starred}) {
    return Message(
      id:        id,
      role:      role,
      content:   content ?? this.content,
      status:    status  ?? this.status,
      starred:   starred ?? this.starred,
      isSummary: isSummary,
      createdAt: createdAt,
    );
  }

  Map<String, dynamic> toJson() => {
    'id':        id,
    'role':      role.name,
    'content':   content,
    'status':    status.name,
    'starred':   starred,
    if (isSummary) 'isSummary': true,
    'createdAt': createdAt.toIso8601String(),
  };

  factory Message.fromJson(Map<String, dynamic> j) => Message(
    id:        j['id'] as String,
    role:      MessageRole.values.byName(j['role'] as String),
    content:   j['content'] as String,
    status:    MessageStatus.values.byName(j['status'] as String? ?? 'done'),
    starred:   j['starred'] as bool? ?? false,
    isSummary: j['isSummary'] as bool? ?? false,
    createdAt: DateTime.parse(j['createdAt'] as String),
  );
}
