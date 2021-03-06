package org.thoughtcrime.securesms.database;


import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import net.sqlcipher.database.SQLiteDatabase;

import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;

import java.io.Closeable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class GroupDatabase extends Database {

  @SuppressWarnings("unused")
  private static final String TAG = GroupDatabase.class.getSimpleName();

          static final String TABLE_NAME          = "groups";
  private static final String ID                  = "_id";
          static final String GROUP_ID            = "group_id";
          static final String RECIPIENT_ID        = "recipient_id";
  private static final String TITLE               = "title";
  private static final String MEMBERS             = "members";
  private static final String AVATAR              = "avatar";
  private static final String AVATAR_ID           = "avatar_id";
  private static final String AVATAR_KEY          = "avatar_key";
  private static final String AVATAR_CONTENT_TYPE = "avatar_content_type";
  private static final String AVATAR_RELAY        = "avatar_relay";
  private static final String AVATAR_DIGEST       = "avatar_digest";
  private static final String TIMESTAMP           = "timestamp";
          static final String ACTIVE              = "active";
          static final String MMS                 = "mms";

  public static final String CREATE_TABLE =
      "CREATE TABLE " + TABLE_NAME +
          " (" + ID + " INTEGER PRIMARY KEY, " +
          GROUP_ID + " TEXT, " +
          RECIPIENT_ID + " INTEGER, " +
          TITLE + " TEXT, " +
          MEMBERS + " TEXT, " +
          AVATAR + " BLOB, " +
          AVATAR_ID + " INTEGER, " +
          AVATAR_KEY + " BLOB, " +
          AVATAR_CONTENT_TYPE + " TEXT, " +
          AVATAR_RELAY + " TEXT, " +
          TIMESTAMP + " INTEGER, " +
          ACTIVE + " INTEGER DEFAULT 1, " +
          AVATAR_DIGEST + " BLOB, " +
          MMS + " INTEGER DEFAULT 0);";

  public static final String[] CREATE_INDEXS = {
      "CREATE UNIQUE INDEX IF NOT EXISTS group_id_index ON " + TABLE_NAME + " (" + GROUP_ID + ");",
      "CREATE UNIQUE INDEX IF NOT EXISTS group_recipient_id_index ON " + TABLE_NAME + " (" + RECIPIENT_ID + ");",
  };

  private static final String[] GROUP_PROJECTION = {
      GROUP_ID, RECIPIENT_ID, TITLE, MEMBERS, AVATAR, AVATAR_ID, AVATAR_KEY, AVATAR_CONTENT_TYPE, AVATAR_RELAY, AVATAR_DIGEST,
      TIMESTAMP, ACTIVE, MMS
  };

  static final List<String> TYPED_GROUP_PROJECTION = Stream.of(GROUP_PROJECTION).map(columnName -> TABLE_NAME + "." + columnName).toList();

  public GroupDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public Optional<GroupRecord> getGroup(RecipientId recipientId) {
    try (Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, RECIPIENT_ID + " = ?", new String[] {recipientId.serialize()}, null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        return getGroup(cursor);
      }

      return Optional.absent();
    }
  }

  public Optional<GroupRecord> getGroup(@NonNull GroupId groupId) {
    try (Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, GROUP_ID + " = ?",
                                                                    new String[] {groupId.toString()},
                                                                    null, null, null))
    {
      if (cursor != null && cursor.moveToNext()) {
        return getGroup(cursor);
      }

      return Optional.absent();
    }
  }

  Optional<GroupRecord> getGroup(Cursor cursor) {
    Reader reader = new Reader(cursor);
    return Optional.fromNullable(reader.getCurrent());
  }

  public boolean isUnknownGroup(@NonNull GroupId groupId) {
    Optional<GroupRecord> group = getGroup(groupId);

    if (!group.isPresent()) {
      return true;
    }

    boolean noMetadata = group.get().getAvatar() == null && TextUtils.isEmpty(group.get().getTitle());
    boolean noMembers  = group.get().getMembers().isEmpty() || (group.get().getMembers().size() == 1 && group.get().getMembers().contains(Recipient.self().getId()));

    return noMetadata && noMembers;
  }

  public Reader getGroupsFilteredByTitle(String constraint, boolean includeInactive) {
    String   query;
    String[] queryArgs;

    if (includeInactive) {
      query     = TITLE + " LIKE ? AND (" + ACTIVE + " = ? OR " + RECIPIENT_ID + " IN (SELECT " + ThreadDatabase.RECIPIENT_ID + " FROM " + ThreadDatabase.TABLE_NAME + "))";
      queryArgs = new String[]{"%" + constraint + "%", "1"};
    } else {
      query     = TITLE + " LIKE ? AND " + ACTIVE + " = ?";
      queryArgs = new String[]{"%" + constraint + "%", "1"};
    }

    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, query, queryArgs, null, null, TITLE + " COLLATE NOCASE ASC");

    return new Reader(cursor);
  }

  public GroupId getOrCreateGroupForMembers(List<RecipientId> members, boolean mms) {
    Collections.sort(members);

    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {GROUP_ID},
                                                               MEMBERS + " = ? AND " + MMS + " = ?",
                                                               new String[] {RecipientId.toSerializedList(members), mms ? "1" : "0"},
                                                               null, null, null);
    try {
      if (cursor != null && cursor.moveToNext()) {
        return GroupId.parse(cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID)));
      } else {
        GroupId groupId = allocateGroupId(mms);
        create(groupId, null, members, null, null);
        return groupId;
      }
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public List<String> getGroupNamesContainingMember(RecipientId recipientId) {
    return Stream.of(getGroupsContainingMember(recipientId))
                 .map(GroupRecord::getTitle)
                 .toList();
  }

  public List<GroupRecord> getGroupsContainingMember(RecipientId recipientId) {
    SQLiteDatabase database   = databaseHelper.getReadableDatabase();
    String         table      = TABLE_NAME + " INNER JOIN " + ThreadDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + RECIPIENT_ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.RECIPIENT_ID;
    String         query      = MEMBERS + " LIKE ?";
    String[]       args       = new String[]{"%" + recipientId.serialize() + "%"};
    String         orderBy    = ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.DATE + " DESC";

    List<GroupRecord> groups = new LinkedList<>();

    try (Cursor cursor = database.query(table, null, query, args, null, null, orderBy)) {
      while (cursor != null && cursor.moveToNext()) {
        List<String> members = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)), ",");

        if (members.contains(recipientId.serialize())) {
          groups.add(new Reader(cursor).getCurrent());
        }
      }
    }

    return groups;
  }

  public Reader getGroups() {
    @SuppressLint("Recycle")
    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, null, null, null, null, null);
    return new Reader(cursor);
  }

  public @NonNull List<Recipient> getGroupMembers(@NonNull GroupId groupId, boolean includeSelf) {
    List<RecipientId> members     = getCurrentMembers(groupId);
    List<Recipient>   recipients  = new LinkedList<>();

    for (RecipientId member : members) {
      if (!includeSelf && Recipient.resolved(member).isLocalNumber()) {
        continue;
      }

      recipients.add(Recipient.resolved(member));
    }

    return recipients;
  }

  public void create(@NonNull GroupId groupId, @Nullable String title, @NonNull List<RecipientId> members,
                     @Nullable SignalServiceAttachmentPointer avatar, @Nullable String relay)
  {
    Collections.sort(members);

    ContentValues contentValues = new ContentValues();
    contentValues.put(RECIPIENT_ID, DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId).serialize());
    contentValues.put(GROUP_ID, groupId.toString());
    contentValues.put(TITLE, title);
    contentValues.put(MEMBERS, RecipientId.toSerializedList(members));

    if (avatar != null) {
      contentValues.put(AVATAR_ID, avatar.getId());
      contentValues.put(AVATAR_KEY, avatar.getKey());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_DIGEST, avatar.getDigest().orNull());
    }

    contentValues.put(AVATAR_RELAY, relay);
    contentValues.put(TIMESTAMP, System.currentTimeMillis());
    contentValues.put(ACTIVE, 1);
    contentValues.put(MMS, groupId.isMmsGroup());

    databaseHelper.getWritableDatabase().insert(TABLE_NAME, null, contentValues);

    RecipientId groupRecipient = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();

    notifyConversationListListeners();
  }

  public void update(@NonNull GroupId groupId, String title, SignalServiceAttachmentPointer avatar) {
    ContentValues contentValues = new ContentValues();
    if (title != null) contentValues.put(TITLE, title);

    if (avatar != null) {
      contentValues.put(AVATAR_ID, avatar.getId());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_KEY, avatar.getKey());
      contentValues.put(AVATAR_DIGEST, avatar.getDigest().orNull());
    }

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues,
                                                GROUP_ID + " = ?",
                                                new String[] {groupId.toString()});

    RecipientId groupRecipient = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();

    notifyConversationListListeners();
  }

  public void updateTitle(@NonNull GroupId groupId, String title) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(TITLE, title);
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID +  " = ?",
                                                new String[] {groupId.toString()});

    RecipientId groupRecipient = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();
  }

  public void updateAvatar(@NonNull GroupId groupId, @Nullable Bitmap avatar) {
    updateAvatar(groupId, BitmapUtil.toByteArray(avatar));
  }

  public void updateAvatar(@NonNull GroupId groupId, @Nullable byte[] avatar) {
    long avatarId;

    if (avatar != null) avatarId = Math.abs(new SecureRandom().nextLong());
    else                avatarId = 0;


    ContentValues contentValues = new ContentValues(2);
    contentValues.put(AVATAR, avatar);
    contentValues.put(AVATAR_ID, avatarId);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID +  " = ?",
                                                new String[] {groupId.toString()});

    RecipientId groupRecipient = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();
  }

  public void updateMembers(@NonNull GroupId groupId, List<RecipientId> members) {
    Collections.sort(members);

    ContentValues contents = new ContentValues();
    contents.put(MEMBERS, RecipientId.toSerializedList(members));
    contents.put(ACTIVE, 1);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?",
                                                new String[] {groupId.toString()});

    RecipientId groupRecipient = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();
  }

  public void remove(@NonNull GroupId groupId, RecipientId source) {
    List<RecipientId> currentMembers = getCurrentMembers(groupId);
    currentMembers.remove(source);

    ContentValues contents = new ContentValues();
    contents.put(MEMBERS, RecipientId.toSerializedList(currentMembers));

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?",
                                                new String[] {groupId.toString()});

    RecipientId groupRecipient = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();
  }

  private List<RecipientId> getCurrentMembers(@NonNull GroupId groupId) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {MEMBERS},
                                                          GROUP_ID + " = ?",
                                                          new String[] {groupId.toString()},
                                                          null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));
        return RecipientId.fromSerializedList(serializedMembers);
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public boolean isActive(@NonNull GroupId groupId) {
    Optional<GroupRecord> record = getGroup(groupId);
    return record.isPresent() && record.get().isActive();
  }

  public void setActive(@NonNull GroupId groupId, boolean active) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(ACTIVE, active ? 1 : 0);
    database.update(TABLE_NAME, values, GROUP_ID + " = ?", new String[] {groupId.toString()});
  }

  public static GroupId allocateGroupId(boolean mms) {
    byte[] groupId = new byte[16];
    new SecureRandom().nextBytes(groupId);
    return mms ? GroupId.mms(groupId) : GroupId.v1(groupId);
  }

  public static class Reader implements Closeable {

    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public @Nullable GroupRecord getNext() {
      if (cursor == null || !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public @Nullable GroupRecord getCurrent() {
      if (cursor == null || cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID)) == null) {
        return null;
      }

      return new GroupRecord(GroupId.parse(cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID))),
                             RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT_ID))),
                             cursor.getString(cursor.getColumnIndexOrThrow(TITLE)),
                             cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)),
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR)),
                             cursor.getLong(cursor.getColumnIndexOrThrow(AVATAR_ID)),
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR_KEY)),
                             cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_CONTENT_TYPE)),
                             cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_RELAY)),
                             cursor.getInt(cursor.getColumnIndexOrThrow(ACTIVE)) == 1,
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR_DIGEST)),
                             cursor.getInt(cursor.getColumnIndexOrThrow(MMS)) == 1);
    }

    @Override
    public void close() {
      if (this.cursor != null)
        this.cursor.close();
    }
  }

  public static class GroupRecord {

    private final GroupId           id;
    private final RecipientId       recipientId;
    private final String            title;
    private final List<RecipientId> members;
    private final byte[]            avatar;
    private final long              avatarId;
    private final byte[]            avatarKey;
    private final byte[]            avatarDigest;
    private final String            avatarContentType;
    private final String            relay;
    private final boolean           active;
    private final boolean           mms;

    public GroupRecord(@NonNull GroupId id, @NonNull RecipientId recipientId, String title, String members, byte[] avatar,
                       long avatarId, byte[] avatarKey, String avatarContentType,
                       String relay, boolean active, byte[] avatarDigest, boolean mms)
    {
      this.id                = id;
      this.recipientId       = recipientId;
      this.title             = title;
      this.avatar            = avatar;
      this.avatarId          = avatarId;
      this.avatarKey         = avatarKey;
      this.avatarDigest      = avatarDigest;
      this.avatarContentType = avatarContentType;
      this.relay             = relay;
      this.active            = active;
      this.mms               = mms;

      if (!TextUtils.isEmpty(members)) this.members = RecipientId.fromSerializedList(members);
      else                             this.members = new LinkedList<>();
    }

    public GroupId getId() {
      return id;
    }

    public @NonNull RecipientId getRecipientId() {
      return recipientId;
    }

    public String getTitle() {
      return title;
    }

    public List<RecipientId> getMembers() {
      return members;
    }

    public byte[] getAvatar() {
      return avatar;
    }

    public long getAvatarId() {
      return avatarId;
    }

    public byte[] getAvatarKey() {
      return avatarKey;
    }

    public byte[] getAvatarDigest() {
      return avatarDigest;
    }

    public String getAvatarContentType() {
      return avatarContentType;
    }

    public String getRelay() {
      return relay;
    }

    public boolean isActive() {
      return active;
    }

    public boolean isMms() {
      return mms;
    }
  }
}
