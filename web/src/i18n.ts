import { tg } from './tg'

const en = {
  groups: 'Groups', noGroups: 'No groups where you can review join requests. Add the bot to a group as an admin first.',
  form: 'Form', submissions: 'Submissions', settings: 'Settings', back: 'Back',
  welcome: 'Welcome message', addField: 'Add field', save: 'Save', saved: 'Saved', preview: 'Preview',
  prompt: 'Question', required: 'Required', other: 'Offer "Other"', options: 'Options', addOption: 'Add option',
  minSel: 'Min choices', maxSel: 'Max choices', min: 'Min', max: 'Max', maxLen: 'Max length',
  radio: 'Choice', yesno: 'Yes / No', multi: 'Multiple choice', text: 'Text', int: 'Number', link: 'Link', consent: 'Consent',
  up: 'Move up', down: 'Move down', del: 'Delete',
  conflict: 'Someone saved in between — reload', reload: 'Reload',
  errors: 'Fix these first', skip: 'Skip', done: 'Done', agree: 'I agree', disagree: 'I disagree', other_: 'Other',
  all: 'All', PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn', EXPIRED: 'Expired',
  empty: 'Nothing here.', deleteSub: 'Delete submission', confirmDelete: 'Delete this submission permanently?', cancel: 'Cancel',
  exportCsv: 'Export CSV', exported: 'Sent to your DM.', exportNoBot: 'Start the bot in a private chat first, then retry.',
  retention: 'Keep submissions for (days)', retentionHint: '1 to 3650', loadFail: 'Could not load. Try again.', retry: 'Retry',
  noAnswers: 'Answers were already erased.', saveFail: 'Request failed. Try again.', noAuth: 'Open this page from the bot menu in Telegram.',
  newForm: 'No form yet.', inactive: 'inactive',
}
const ru: typeof en = {
  groups: 'Группы', noGroups: 'Нет групп, где вы можете рассматривать заявки. Сначала добавьте бота в группу как админа.',
  form: 'Анкета', submissions: 'Заявки', settings: 'Настройки', back: 'Назад',
  welcome: 'Приветствие', addField: 'Добавить поле', save: 'Сохранить', saved: 'Сохранено', preview: 'Предпросмотр',
  prompt: 'Вопрос', required: 'Обязательный', other: 'Вариант «Другое»', options: 'Варианты', addOption: 'Добавить вариант',
  minSel: 'Мин. выбор', maxSel: 'Макс. выбор', min: 'Мин', max: 'Макс', maxLen: 'Макс. длина',
  radio: 'Один из списка', yesno: 'Да / Нет', multi: 'Несколько из списка', text: 'Текст', int: 'Число', link: 'Ссылка', consent: 'Согласие',
  up: 'Вверх', down: 'Вниз', del: 'Удалить',
  conflict: 'Кто-то сохранил раньше — перезагрузите', reload: 'Перезагрузить',
  errors: 'Сначала исправьте', skip: 'Пропустить', done: 'Готово', agree: 'Согласен', disagree: 'Не согласен', other_: 'Другое',
  all: 'Все', PENDING: 'Ожидают', APPROVED: 'Одобрены', REJECTED: 'Отклонены', WITHDRAWN: 'Отозваны', EXPIRED: 'Истекли',
  empty: 'Здесь пусто.', deleteSub: 'Удалить заявку', confirmDelete: 'Удалить заявку навсегда?', cancel: 'Отмена',
  exportCsv: 'Экспорт CSV', exported: 'Отправлено вам в личные сообщения.', exportNoBot: 'Сначала запустите бота в личном чате и повторите.',
  retention: 'Хранить заявки (дней)', retentionHint: 'от 1 до 3650', loadFail: 'Не удалось загрузить. Повторите.', retry: 'Повторить',
  noAnswers: 'Ответы уже стёрты.', saveFail: 'Запрос не удался. Повторите.', noAuth: 'Откройте страницу из меню бота в Telegram.',
  newForm: 'Анкеты пока нет.', inactive: 'неактивна',
}
export const t: typeof en = tg?.initDataUnsafe.user?.language_code?.startsWith('ru') ? ru : en
