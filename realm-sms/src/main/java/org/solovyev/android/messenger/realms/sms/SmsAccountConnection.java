package org.solovyev.android.messenger.realms.sms;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import org.joda.time.DateTime;
import org.solovyev.android.messenger.App;
import org.solovyev.android.messenger.accounts.Account;
import org.solovyev.android.messenger.accounts.AccountConnectionException;
import org.solovyev.android.messenger.accounts.AccountException;
import org.solovyev.android.messenger.accounts.connection.AbstractAccountConnection;
import org.solovyev.android.messenger.chats.Chat;
import org.solovyev.android.messenger.chats.ChatService;
import org.solovyev.android.messenger.messages.Message;
import org.solovyev.android.messenger.messages.MessageState;
import org.solovyev.android.messenger.messages.MutableMessage;
import org.solovyev.android.messenger.users.MutableUser;
import org.solovyev.android.messenger.users.PhoneNumber;
import org.solovyev.android.messenger.users.User;
import org.solovyev.android.messenger.users.UserService;
import org.solovyev.android.properties.MutableAProperties;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

import static android.telephony.SmsMessage.createFromPdu;
import static com.google.common.collect.Iterables.any;
import static org.solovyev.android.messenger.App.getApplication;
import static org.solovyev.android.messenger.App.getChatService;
import static org.solovyev.android.messenger.App.getMessageService;
import static org.solovyev.android.messenger.accounts.AccountService.NO_ACCOUNT_ID;
import static org.solovyev.android.messenger.entities.Entities.*;
import static org.solovyev.android.messenger.messages.MessageState.delivered;
import static org.solovyev.android.messenger.messages.MessageState.received;
import static org.solovyev.android.messenger.messages.MessageState.sent;
import static org.solovyev.android.messenger.messages.Messages.newMessage;
import static org.solovyev.android.messenger.realms.sms.SmsRealm.*;
import static org.solovyev.android.messenger.users.PhoneNumber.newPhoneNumber;
import static org.solovyev.android.messenger.users.User.PROPERTY_PHONE;
import static org.solovyev.android.messenger.users.User.PROPERTY_PHONES;
import static org.solovyev.android.messenger.users.Users.newEmptyUser;
import static org.solovyev.common.text.Strings.isEmpty;

/**
 * User: serso
 * Date: 5/27/13
 * Time: 9:22 PM
 */
final class SmsAccountConnection extends AbstractAccountConnection<SmsAccount> {

	@Nullable
	private volatile ReportsBroadcastReceiver receiver;

	SmsAccountConnection(@Nonnull SmsAccount account, @Nonnull Context context) {
		super(account, context, false);
	}

	@Override
	protected void start0() throws AccountConnectionException {
		if (receiver == null) {
			receiver = new ReportsBroadcastReceiver();
			final Application application = getApplication();
			application.registerReceiver(receiver, new IntentFilter(INTENT_SENT));
			application.registerReceiver(receiver, new IntentFilter(INTENT_DELIVERED));

			final IntentFilter intentReceivedFilter = new IntentFilter(INTENT_RECEIVED);
			intentReceivedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
			application.registerReceiver(receiver, intentReceivedFilter);
		}
	}

	@Override
	protected void stop0() {
		unregisterReceiver();
	}

	private void unregisterReceiver() {
		if (receiver != null) {
			getApplication().unregisterReceiver(receiver);
			receiver = null;
		}
	}

	private class ReportsBroadcastReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			try {
				final String action = intent.getAction();
				if (action.equals(INTENT_RECEIVED)) {
					onSmsReceived(this, intent);
				} else if (action.equals(INTENT_SENT)) {
					onSmsIntent(intent, sent);
				} else if (action.equals(INTENT_DELIVERED)) {
					onSmsIntent(intent, delivered);
				}

			} catch (AccountException e) {
				Log.e(SmsRealm.TAG, e.getMessage(), e);
			}
		}
	}

	private void onSmsIntent(@Nonnull Intent intent, @Nonnull MessageState state) {
		final String entityId = intent.getStringExtra(INTENT_EXTRA_SMS_ID);
		if (!isEmpty(entityId)) {
			final Message message = getMessageService().getMessage(entityId);
			if (message != null) {
				getChatService().updateMessageState(message.cloneWithNewState(state));
			}
		}
	}

	private void onSmsReceived(@Nonnull BroadcastReceiver broadcastReceiver, @Nonnull Intent intent) throws AccountException {
		final SmsAccount account = getAccount();
		final Multimap<String, String> messagesByPhoneNumber = getMessagesByPhoneNumber(intent);

		if (!messagesByPhoneNumber.isEmpty()) {
			final User user = account.getUser();
			final UserService userService = App.getUserService();
			final ChatService chatService = getChatService();

			final List<User> contacts = userService.getUserContacts(user.getEntity());

			for (Map.Entry<String, Collection<String>> entry : messagesByPhoneNumber.asMap().entrySet()) {
				final User contact = findOrCreateContact(entry.getKey(), contacts);
				final Chat chat = chatService.getOrCreatePrivateChat(user.getEntity(), contact.getEntity());

				final List<Message> messages = new ArrayList<Message>(entry.getValue().size());
				for (String messageBode : entry.getValue()) {
					final Message message = toMessage(messageBode, account, contact, user, chat);
					if (message != null) {
						messages.add(message);
					}
				}

				chatService.saveMessages(chat.getEntity(), messages);
			}
		}

		if (account.getConfiguration().isStopFurtherProcessing()) {
			broadcastReceiver.abortBroadcast();
		}
	}

	@Nonnull
	private Multimap<String, String> getMessagesByPhoneNumber(@Nonnull Intent intent) {
		final Multimap<String, String> smss = ArrayListMultimap.create();

		final Bundle extras = intent.getExtras();
		if (extras != null) {
			final Object[] smsExtras = (Object[]) extras.get(SmsRealm.INTENT_EXTRA_PDUS);
			final String smsFormat = extras.getString(SmsRealm.INTENT_EXTRA_FORMAT);

			String fromAddress = null;
			final StringBuilder message = new StringBuilder(255 * smsExtras.length);
			for (Object smsExtra : smsExtras) {
				final SmsMessage smsPart = createFromPdu((byte[]) smsExtra);
				message.append(smsPart.getMessageBody());
				fromAddress = smsPart.getOriginatingAddress();
			}

			if (!isEmpty(message) && !isEmpty(fromAddress)) {
				smss.put(fromAddress, message.toString());
			}
		}

		return smss;
	}

	@Nullable
	private Message toMessage(@Nonnull String messageBody, @Nonnull Account account, @Nonnull User from, @Nonnull User to, @Nonnull Chat chat) {
		if (!isEmpty(messageBody)) {
			final MutableMessage message = newMessage(generateEntity(account));
			message.setChat(chat.getEntity());
			message.setBody(messageBody);
			message.setAuthor(from.getEntity());
			message.setRecipient(to.getEntity());
			message.setSendDate(DateTime.now());
			message.setState(received);
			message.setRead(false);
			return message;
		} else {
			return null;
		}
	}

	@Nullable
	private User findOrCreateContact(@Nonnull final String phone, @Nonnull List<User> contacts) {
		User result = findContactByPhone(phone, contacts);
		if (result == null) {
			result = toUser(phone);

			final SmsAccount account = getAccount();
			App.getUserService().mergeUserContacts(account.getUser().getEntity(), Arrays.asList(result), false, false);
		}
		return result;
	}

	@Nonnull
	private User toUser(@Nonnull String phone) {
		return toUser(newPhoneNumber(phone));
	}

	@Nonnull
	private User toUser(@Nonnull PhoneNumber phoneNumber) {
		final SmsAccount account = getAccount();

		final MutableUser user = newEmptyUser(newEntity(account.getId(), NO_ACCOUNT_ID, makeEntityId(account.getId(), phoneNumber.getNumber())));
		user.setFirstName(phoneNumber.getNumber());

		final MutableAProperties properties = user.getProperties();
		if (phoneNumber.isValid()) {
			properties.setProperty(PROPERTY_PHONE, phoneNumber.getNumber());
			properties.setProperty(PROPERTY_PHONES, phoneNumber.getNumber());
		}

		return user;
	}

	@Nullable
	private User findContactByPhone(@Nonnull final String phone, @Nonnull List<User> contacts) {
		final SamePhonePredicate predicate = new SamePhonePredicate(newPhoneNumber(phone));

		return Iterables.find(contacts, new Predicate<User>() {
			@Override
			public boolean apply(@Nullable User contact) {
				if (contact != null) {
					// first try to find by default phone property
					if (predicate.apply(contact.getPropertyValueByName(PROPERTY_PHONE))) {
						return true;
					} else {
						return any(contact.getPhoneNumbers(), predicate);
					}
				} else {
					return false;
				}
			}
		}, null);
	}

	private static class SamePhonePredicate implements Predicate<String> {

		@Nonnull
		private final PhoneNumber phoneNumber;

		public SamePhonePredicate(@Nonnull PhoneNumber phoneNumber) {
			this.phoneNumber = phoneNumber;
		}

		@Override
		public boolean apply(@Nullable String phone) {
			if (phone != null) {
				if (newPhoneNumber(phone).same(phoneNumber)) {
					return true;
				}
			}

			return false;
		}
	}
}
