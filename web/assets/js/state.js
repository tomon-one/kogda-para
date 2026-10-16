// Состояние страницы — одно на отрисовку, переходы и обновление: поля
// объектов меняет любой из них, сами объекты не подменяются.

export var root = document.getElementById('app');

export var state = {
  refreshing: false,
  flash: false,
  refreshFailed: false,
  groups: null,
  teachers: null,
  /** Лист пересдач (ответ /v1/retakes); null — грузится, 'failed' — не загрузился. */
  retakes: null,
  /** Аудитории по дню: ответ /v1/rooms, 'failed' — не загрузились, 'missing' — дня в листе нет. */
  rooms: {},
  roomsUi: null,
  other: null,
  queries: {},
  /**
   * Что было открыто на вкладке — адрес после «#» («teachers/<id>», «groups»,
   * «» — своё): вернулся на неё — туда же, а не к списку и поиску заново.
   */
  opened: {},
  /** День, к которому надо прокрутить, когда он появится; null — человек листает сам. */
  wanted: null,
  lastRefresh: 0,
  /** Когда начал крутиться ⟳: новый значок после перестройки продолжает с той же фазы. */
  spinStart: 0,
  /** Запрос кончился, значок доводит оборот. */
  spinningDown: false,
  flashAt: 0,
  /** Почему не загрузились пересдачи ('retakes') и аудитории дня ('rooms:<день>'): 'server' или 'net'. */
  failedBy: {},
};

/** Въезд экрана: каким классом и когда начался — перестройка его продолжает. */
export var nav = { cls: null, at: 0, depth: null, appear: false };

/**
 * Сайт для экранов (ui/): что показать и что делать по нажатию. Методы ему
 * даёт main.js при запуске, до первой отрисовки.
 */
export var app = {};
