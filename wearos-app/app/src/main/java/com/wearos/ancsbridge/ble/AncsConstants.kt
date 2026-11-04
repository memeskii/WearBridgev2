package com.wearos.ancsbridge.ble

import java.util.UUID

/**
 * Apple Notification Center Service (ANCS) protocol constants.
 * Reference: https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html
 */
object AncsConstants {

    // ANCS Service UUID
    val ANCS_SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")

    // iOS Companion App Service UUID (advertised by the WearBridge iOS app)
    val COMPANION_SERVICE_UUID: UUID = UUID.fromString("A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B")

    // Quick Reply Characteristic UUID (writable — sends reply text to iPhone)
    val QUICK_REPLY_CHARACTERISTIC_UUID: UUID = UUID.fromString("7F8A0003-9B3C-4D2A-B9E1-8C5F6D7E8A01")

    // Call State Characteristic UUID (readable, notifiable — iPhone pushes call state to watch)
    val CALL_STATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("A0E3D5B4-4C6F-4E8A-9B2D-7F1E3A5C8D0B")

    // Call state values (matches iOS BLEConstants)
    const val CALL_STATE_IDLE: Byte = 0x00
    const val CALL_STATE_RINGING: Byte = 0x01
    const val CALL_STATE_ACTIVE: Byte = 0x02
    const val CALL_STATE_ENDED: Byte = 0x03

    // ANCS Characteristic UUIDs
    val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    // Client Characteristic Configuration Descriptor
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Event IDs (Notification Source byte 0)
    const val EVENT_ID_ADDED = 0
    const val EVENT_ID_MODIFIED = 1
    const val EVENT_ID_REMOVED = 2

    // Event Flags (Notification Source byte 1, bitmask)
    const val EVENT_FLAG_SILENT = 0x01
    const val EVENT_FLAG_IMPORTANT = 0x02
    const val EVENT_FLAG_PRE_EXISTING = 0x04
    const val EVENT_FLAG_POSITIVE_ACTION = 0x08
    const val EVENT_FLAG_NEGATIVE_ACTION = 0x10

    // Category IDs (Notification Source byte 2)
    const val CATEGORY_OTHER = 0
    const val CATEGORY_INCOMING_CALL = 1
    const val CATEGORY_MISSED_CALL = 2
    const val CATEGORY_VOICEMAIL = 3
    const val CATEGORY_SOCIAL = 4
    const val CATEGORY_SCHEDULE = 5
    const val CATEGORY_EMAIL = 6
    const val CATEGORY_NEWS = 7
    const val CATEGORY_HEALTH_FITNESS = 8
    const val CATEGORY_BUSINESS_FINANCE = 9
    const val CATEGORY_LOCATION = 10
    const val CATEGORY_ENTERTAINMENT = 11

    // Command IDs (Control Point)
    const val COMMAND_GET_NOTIFICATION_ATTRIBUTES = 0
    const val COMMAND_GET_APP_ATTRIBUTES = 1
    const val COMMAND_PERFORM_NOTIFICATION_ACTION = 2

    // Notification Attribute IDs
    const val ATTR_APP_IDENTIFIER = 0
    const val ATTR_TITLE = 1
    const val ATTR_SUBTITLE = 2
    const val ATTR_MESSAGE = 3
    const val ATTR_MESSAGE_SIZE = 4
    const val ATTR_DATE = 5
    const val ATTR_POSITIVE_ACTION_LABEL = 6
    const val ATTR_NEGATIVE_ACTION_LABEL = 7

    // App Attribute IDs
    const val APP_ATTR_DISPLAY_NAME = 0

    // Action IDs (PerformNotificationAction)
    const val ACTION_POSITIVE = 0
    const val ACTION_NEGATIVE = 1
    const val ACTION_ID_POSITIVE = 0
    const val ACTION_ID_NEGATIVE = 1

    // Attributes that take a max-length parameter (2 bytes LE)
    val VARIABLE_LENGTH_ATTRIBUTES = setOf(
        ATTR_TITLE, ATTR_SUBTITLE, ATTR_MESSAGE,
        ATTR_POSITIVE_ACTION_LABEL, ATTR_NEGATIVE_ACTION_LABEL
    )

    // Default max length for variable-length attributes
    const val DEFAULT_MAX_ATTRIBUTE_LENGTH = 255

    // ANCS Error Codes (returned via ATT error response)
    const val ERROR_UNKNOWN_COMMAND = 0xA0
    const val ERROR_INVALID_COMMAND = 0xA1
    const val ERROR_INVALID_PARAMETER = 0xA2
    const val ERROR_ACTION_FAILED = 0xA3

    // MARK: - Apple Media Service (AMS) Protocol Constants
    val AMS_SERVICE_UUID: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
    val AMS_REMOTE_COMMAND_UUID: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
    val AMS_ENTITY_UPDATE_UUID: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
    val AMS_ENTITY_ATTRIBUTE_UUID: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870B5D770")

    // AMS Entity IDs
    const val AMS_ENTITY_ID_PLAYER: Byte = 0x00
    const val AMS_ENTITY_ID_QUEUE: Byte = 0x01
    const val AMS_ENTITY_ID_TRACK: Byte = 0x02

    // AMS Player Attribute IDs
    const val AMS_PLAYER_ATTR_NAME: Byte = 0x00
    const val AMS_PLAYER_ATTR_PLAYBACK_INFO: Byte = 0x01
    const val AMS_PLAYER_ATTR_VOLUME: Byte = 0x02

    // AMS Queue Attribute IDs
    const val AMS_QUEUE_ATTR_INDEX: Byte = 0x00
    const val AMS_QUEUE_ATTR_COUNT: Byte = 0x01
    const val AMS_QUEUE_ATTR_SHUFFLE_MODE: Byte = 0x02
    const val AMS_QUEUE_ATTR_REPEAT_MODE: Byte = 0x03

    // AMS Track Attribute IDs
    const val AMS_TRACK_ATTR_ARTIST: Byte = 0x00
    const val AMS_TRACK_ATTR_ALBUM: Byte = 0x01
    const val AMS_TRACK_ATTR_TITLE: Byte = 0x02
    const val AMS_TRACK_ATTR_DURATION: Byte = 0x03

    // AMS Flags
    const val AMS_ENTITY_UPDATE_FLAG_TRUNCATED: Byte = 0x01

    // AMS Remote Commands
    const val AMS_REMOTE_CMD_PLAY: Byte = 0x00
    const val AMS_REMOTE_CMD_PAUSE: Byte = 0x01
    const val AMS_REMOTE_CMD_TOGGLE_PLAY_PAUSE: Byte = 0x02
    const val AMS_REMOTE_CMD_NEXT_TRACK: Byte = 0x03
    const val AMS_REMOTE_CMD_PREVIOUS_TRACK: Byte = 0x04
    const val AMS_REMOTE_CMD_VOLUME_UP: Byte = 0x05
    const val AMS_REMOTE_CMD_VOLUME_DOWN: Byte = 0x06
    const val AMS_REMOTE_CMD_ADVANCE_REPEAT_MODE: Byte = 0x07
    const val AMS_REMOTE_CMD_ADVANCE_SHUFFLE_MODE: Byte = 0x08
    const val AMS_REMOTE_CMD_SKIP_FORWARD: Byte = 0x09
    const val AMS_REMOTE_CMD_SKIP_BACKWARD: Byte = 0x0A
    const val AMS_REMOTE_CMD_LIKE_TRACK: Byte = 0x0B
    const val AMS_REMOTE_CMD_DISLIKE_TRACK: Byte = 0x0C
    const val AMS_REMOTE_CMD_BOOKMARK_TRACK: Byte = 0x0D
}
