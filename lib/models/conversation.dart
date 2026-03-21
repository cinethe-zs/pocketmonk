import 'package:uuid/uuid.dart';
import 'message.dart';

class Conversation {
  final String   id;
  String         title;
  final DateTime createdAt;
  DateTime       updatedAt;
  String         modelPath;
  String?        systemPrompt;   // null = use global default
  List<String>   tags;
  final List<Message> messages;

  Conversation({
    String? id,
    required this.title,
    required this.modelPath,
    this.systemPrompt,
    List<String>? tags,
    DateTime? createdAt,
    DateTime? updatedAt,
    List<Message>? messages,
  })  : id        = id ?? const Uuid().v4(),
        tags      = tags ?? [],
        createdAt = createdAt ?? DateTime.now(),
        updatedAt = updatedAt ?? DateTime.now(),
        messages  = messages ?? [];

  Map<String, dynamic> toJson() => {
    'id':           id,
    'title':        title,
    'modelPath':    modelPath,
    if (systemPrompt != null) 'systemPrompt': systemPrompt,
    'tags':         tags,
    'createdAt':    createdAt.toIso8601String(),
    'updatedAt':    updatedAt.toIso8601String(),
    'messages':     messages.map((m) => m.toJson()).toList(),
  };

  factory Conversation.fromJson(Map<String, dynamic> j) => Conversation(
    id:           j['id'] as String,
    title:        j['title'] as String,
    modelPath:    j['modelPath'] as String? ?? '',
    systemPrompt: j['systemPrompt'] as String?,
    tags:         (j['tags'] as List<dynamic>?)?.cast<String>() ?? [],
    createdAt:    DateTime.parse(j['createdAt'] as String),
    updatedAt:    DateTime.parse(j['updatedAt'] as String),
    messages:     (j['messages'] as List<dynamic>)
        .map((m) => Message.fromJson(m as Map<String, dynamic>))
        .toList(),
  );
}
