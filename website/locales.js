export const en = {
  skip: 'Skip to content', language: 'Language', system: 'System language',
  eyebrow: 'An open-source Android workstation', headline: 'Your dev workstation.\nNow on Android.',
  intro: 'Official OpenCode. A real Debian environment. Your projects, terminal and tools, together on your phone.',
  apk: 'Download APK', core: 'OpenCode update', requirements: 'Android 10+ · ARM64 · No root',
  updateHint: 'New installation? Choose the APK. The update package upgrades the OpenCode frontend and backend together.',
  copyFeed: 'Copy signed update feed', copied: 'Update feed copied.', copyFailed: 'Select and copy the update feed below.',
  workspace: 'ONE WORKSPACE', illustration: 'Illustrative workspace · Runs on your device',
  toolsEyebrow: 'SMALL DEVICE. REAL TOOLS.', toolsTitle: 'More than a chat window.',
  agentTitle: 'The official experience', agentBody: 'Matching upstream CLI and WebUI, with Agent, sessions, shell, LSP, MCP and plugins.',
  toolsHeading: 'A complete toolbelt', toolsBody: 'curl, wget, Git, Node.js, Python, Rust and Clang are bundled. Keep building with apt.',
  desktopTitle: 'Room to do more', desktopBody: 'Switch between terminals, files and an optional XFCE desktop. Your projects stay together.',
  startEyebrow: 'GET STARTED', startTitle: 'Your next project starts here.', guide: 'Read the installation guide',
  step1: 'Install the ARM64 APK and prepare the bundled runtime.', step2: 'Connect your chosen cloud provider or compatible local model.', step3: 'Open your workspace. Code, run commands, and build.',
  preview: 'ENGINEERING PREVIEW', previewBody: 'Physical-device validation currently centers on Android 16 / K30 / 4 KiB. The broader device, stability and audit matrix is still in progress.',
  validation: 'See validation', unofficial: 'An independent, unofficial port. GPL-3.0-or-later.', allAssets: 'All release files', issues: 'Report an issue', downloads: 'asset downloads',
  downloadTitle: 'Your download is ready.', downloadBody: 'The file will download from GitHub. If it does not start, use the button below.', back: 'Back to the website',
};

const keys = Object.keys(en);
function pack(name, dir, text) {
  const rows = text.trim().split('\n');
  if (rows.length !== keys.length) throw new Error(`${name}: expected ${keys.length} translations, got ${rows.length}`);
  return { name, dir, text: Object.fromEntries(keys.map((key, index) => [key, rows[index].replaceAll('↵', '\n')])) };
}

export const locales = {
  en: { name: 'English', dir: 'ltr', text: en },
  'zh-Hans': pack('简体中文', 'ltr', `
跳转到正文
语言
跟随系统
开源 Android 开发工作站
随身开发工作站。↵现在，就在 Android。
官方 OpenCode，真正的 Debian 环境。项目、终端与开发工具，都在你的手机里。
下载 APK
内核升级包
Android 10+ · ARM64 · 无需 root
首次安装请选择 APK。内核升级包会一起更新匹配的 OpenCode 前端与后端。
复制签名更新源
已复制更新源。
请选中并复制下方更新源。
同一个工作区
工作区示意 · 在你的设备本地运行
小设备，真工具。
不止一个聊天窗口。
原汁原味的官方体验
匹配的上游 CLI 与 WebUI，保留 Agent、会话、Shell、LSP、MCP 与插件。
完整的开发工具箱
内置 curl、wget、Git、Node.js、Python、Rust 与 Clang，还可通过 apt 继续扩展。
为更多工作留出空间
在终端、文件与可选的 XFCE 桌面间切换，所有工具共享你的项目。
开始使用
下一个项目，从这里开始。
查看安装指南
安装 ARM64 APK，准备内置运行环境。
连接你选择的云模型服务或兼容的本地模型。
打开工作区，编写代码、执行命令并构建项目。
工程预发布
当前真机验证主要覆盖 Android 16 / K30 / 4 KiB。更多设备、稳定性与完整审查仍在推进。
查看验收记录
独立、非官方移植项目。GPL-3.0-or-later。
所有发布文件
反馈问题
次资产下载
下载已准备就绪。
文件将从 GitHub 下载；若未自动开始，请点击下方按钮。
返回官网`),
  'zh-Hant': pack('繁體中文', 'ltr', `
跳至主要內容
語言
跟隨系統
開源 Android 開發工作站
隨身開發工作站。↵現在，就在 Android。
官方 OpenCode，真正的 Debian 環境。專案、終端與開發工具，都在你的手機裡。
下載 APK
核心升級套件
Android 10+ · ARM64 · 無需 root
首次安裝請選 APK。核心升級套件會一併更新相符的 OpenCode 前端與後端。
複製簽署的更新來源
已複製更新來源。
請選取並複製下方更新來源。
同一個工作區
工作區示意 · 在你的裝置本機執行
小裝置，真工具。
不只是一個聊天視窗。
原汁原味的官方體驗
相符的上游 CLI 與 WebUI，保留 Agent、工作階段、Shell、LSP、MCP 與外掛。
完整的開發工具箱
內建 curl、wget、Git、Node.js、Python、Rust 與 Clang，也能透過 apt 擴充。
為更多工作留出空間
在終端、檔案與選用的 XFCE 桌面之間切換，工具共享你的專案。
開始使用
下一個專案，從這裡開始。
查看安裝指南
安裝 ARM64 APK，準備內建執行環境。
連接你選擇的雲端模型服務或相容的本機模型。
開啟工作區、撰寫程式碼、執行命令並建置專案。
工程預覽版本
目前實機驗證主要涵蓋 Android 16 / K30 / 4 KiB。更多裝置、穩定性與完整審查仍在進行。
查看驗證記錄
獨立、非官方移植專案。GPL-3.0-or-later。
所有發行檔案
回報問題
次資產下載
下載已準備就緒。
檔案將從 GitHub 下載；若未自動開始，請點選下方按鈕。
返回網站`),
  ja: pack('日本語', 'ltr', `
本文へ移動
言語
システムの言語
オープンソースの Android 開発環境
開発環境を、↵Android に。
公式 OpenCode と本物の Debian 環境。プロジェクト、ターミナル、開発ツールをスマートフォンに。
APK をダウンロード
OpenCode 更新パッケージ
Android 10+ · ARM64 · root 不要
初回は APK を選択してください。更新パッケージは対応する OpenCode のフロントエンドとバックエンドを一緒に更新します。
署名済み更新フィードをコピー
更新フィードをコピーしました。
下の更新フィードを選択してコピーしてください。
ひとつのワークスペース
ワークスペースのイメージ · 端末内で実行
小さな端末に、本物のツール。
チャット画面だけではありません。
公式の体験をそのままに
対応する上流 CLI と WebUI。Agent、セッション、Shell、LSP、MCP、プラグインを利用できます。
充実した開発ツール
curl、wget、Git、Node.js、Python、Rust、Clang を同梱。apt でさらに拡張できます。
もっと自由な作業空間
ターミナル、ファイル、任意の XFCE デスクトップを切り替え、同じプロジェクトで作業できます。
はじめる
次のプロジェクトを、ここから。
インストールガイド
ARM64 APK をインストールし、同梱環境を準備します。
選んだクラウドサービス、または対応するローカルモデルに接続します。
ワークスペースでコードを書き、コマンドを実行してビルドします。
開発プレビュー
実機検証は主に Android 16 / K30 / 4 KiB で実施しています。他の端末、安定性、完全な監査は検証中です。
検証記録を見る
独立した非公式移植です。GPL-3.0-or-later。
すべてのリリースファイル
問題を報告
アセットのダウンロード
ダウンロードの準備ができました。
GitHub からダウンロードします。開始しない場合は下のボタンを押してください。
サイトに戻る`),
  ko: pack('한국어', 'ltr', `
본문으로 이동
언어
시스템 언어
오픈 소스 Android 개발 워크스테이션
개발 워크스테이션을,↵이제 Android에서.
공식 OpenCode와 실제 Debian 환경. 프로젝트, 터미널, 개발 도구를 휴대폰에서 함께 사용하세요.
APK 다운로드
OpenCode 업데이트
Android 10+ · ARM64 · root 불필요
처음 설치한다면 APK를 선택하세요. 업데이트 패키지는 호환되는 OpenCode 프런트엔드와 백엔드를 함께 업데이트합니다.
서명된 업데이트 피드 복사
업데이트 피드를 복사했습니다.
아래 업데이트 피드를 선택해 복사하세요.
하나의 작업 공간
작업 공간 예시 · 기기에서 로컬 실행
작은 기기, 실제 개발 도구.
채팅 창 그 이상.
공식 경험 그대로
호환되는 업스트림 CLI와 WebUI. Agent, 세션, Shell, LSP, MCP와 플러그인을 제공합니다.
완전한 개발 도구 모음
curl, wget, Git, Node.js, Python, Rust, Clang이 포함됩니다. apt로 더 확장하세요.
더 넓은 작업 공간
터미널, 파일, 선택 사항인 XFCE 데스크톱 사이를 전환하며 프로젝트를 공유하세요.
시작하기
다음 프로젝트는 여기에서.
설치 가이드 읽기
ARM64 APK를 설치하고 내장 실행 환경을 준비하세요.
선택한 클라우드 서비스 또는 호환되는 로컬 모델을 연결하세요.
작업 공간에서 코드를 작성하고 명령을 실행하며 빌드하세요.
개발 프리뷰
실기기 검증은 주로 Android 16 / K30 / 4 KiB에서 진행했습니다. 더 넓은 기기, 안정성, 감사 검증은 진행 중입니다.
검증 기록 보기
독립적인 비공식 이식 프로젝트. GPL-3.0-or-later.
모든 릴리스 파일
문제 신고
개 파일 다운로드
다운로드 준비가 완료되었습니다.
GitHub에서 파일을 다운로드합니다. 시작되지 않으면 아래 버튼을 누르세요.
사이트로 돌아가기`),
  de: pack('Deutsch', 'ltr', `
Zum Inhalt
Sprache
Systemsprache
Eine offene Android-Entwicklungsumgebung
Dein Arbeitsplatz.↵Jetzt auf Android.
Offizielles OpenCode. Eine echte Debian-Umgebung. Projekte, Terminal und Werkzeuge auf deinem Smartphone.
APK herunterladen
OpenCode-Update
Android 10+ · ARM64 · Ohne Root
Neu hier? Wähle die APK. Das Updatepaket aktualisiert die passende OpenCode-Oberfläche und den Server gemeinsam.
Signierten Updatefeed kopieren
Updatefeed kopiert.
Wähle und kopiere den Updatefeed unten.
EIN ARBEITSBEREICH
Beispielansicht · Läuft auf deinem Gerät
KLEINES GERÄT. ECHTE WERKZEUGE.
Mehr als ein Chatfenster.
Das offizielle Erlebnis
Passende Upstream-CLI und WebUI mit Agent, Sitzungen, Shell, LSP, MCP und Plugins.
Alle Werkzeuge dabei
curl, wget, Git, Node.js, Python, Rust und Clang sind enthalten. Mit apt erweiterbar.
Platz für mehr
Wechsle zwischen Terminals, Dateien und optionalem XFCE-Desktop. Deine Projekte bleiben verbunden.
ERSTE SCHRITTE
Dein nächstes Projekt beginnt hier.
Installationsanleitung lesen
Installiere die ARM64-APK und bereite die enthaltene Laufzeit vor.
Verbinde deinen Cloudanbieter oder ein kompatibles lokales Modell.
Öffne deinen Arbeitsbereich, schreibe Code, führe Befehle aus und baue Projekte.
ENTWICKLUNGSVORSCHAU
Die Geräteprüfung konzentriert sich derzeit auf Android 16 / K30 / 4 KiB. Weitere Geräte-, Stabilitäts- und Auditprüfungen laufen noch.
Prüfbericht ansehen
Eine unabhängige, inoffizielle Portierung. GPL-3.0-or-later.
Alle Releasedateien
Problem melden
Dateidownloads
Dein Download ist bereit.
Die Datei wird von GitHub geladen. Falls der Download nicht startet, klicke unten.
Zur Website`),
  fr: pack('Français', 'ltr', `
Aller au contenu
Langue
Langue du système
Un poste de développement Android libre
Votre atelier de code.↵Sur Android.
OpenCode officiel. Un véritable environnement Debian. Vos projets, votre terminal et vos outils sur votre téléphone.
Télécharger l’APK
Mise à jour OpenCode
Android 10+ · ARM64 · Sans root
Première installation ? Choisissez l’APK. Le paquet met à jour ensemble l’interface et le serveur OpenCode correspondants.
Copier le flux de mise à jour signé
Flux copié.
Sélectionnez et copiez le flux ci-dessous.
UN SEUL ESPACE DE TRAVAIL
Espace illustratif · Exécution sur votre appareil
PETIT APPAREIL. VRAIS OUTILS.
Bien plus qu’une fenêtre de chat.
L’expérience officielle
CLI et WebUI amont assorties : Agent, sessions, Shell, LSP, MCP et extensions.
Une boîte à outils complète
curl, wget, Git, Node.js, Python, Rust et Clang sont inclus. Continuez avec apt.
De la place pour aller plus loin
Passez des terminaux aux fichiers ou au bureau XFCE facultatif, avec les mêmes projets.
PREMIERS PAS
Votre prochain projet commence ici.
Lire le guide d’installation
Installez l’APK ARM64 et préparez l’environnement fourni.
Connectez le fournisseur cloud choisi ou un modèle local compatible.
Ouvrez votre espace, codez, exécutez des commandes et compilez.
PRÉVERSION DE DÉVELOPPEMENT
La validation réelle concerne surtout Android 16 / K30 / 4 KiB. Les autres appareils, la stabilité et les audits restent en cours.
Voir la validation
Un portage indépendant et non officiel. GPL-3.0-or-later.
Tous les fichiers de version
Signaler un problème
téléchargements de fichiers
Votre téléchargement est prêt.
Le fichier sera téléchargé depuis GitHub. Sinon, utilisez le bouton ci-dessous.
Retour au site`),
  es: pack('Español', 'ltr', `
Ir al contenido
Idioma
Idioma del sistema
Una estación de desarrollo Android de código abierto
Tu espacio de desarrollo.↵Ahora en Android.
OpenCode oficial. Un entorno Debian real. Proyectos, terminal y herramientas juntos en tu teléfono.
Descargar APK
Actualizar OpenCode
Android 10+ · ARM64 · Sin root
¿Primera instalación? Elige el APK. El paquete actualiza juntos la interfaz y el servidor de OpenCode correspondientes.
Copiar fuente de actualización firmada
Fuente de actualización copiada.
Selecciona y copia la fuente de abajo.
UN ESPACIO DE TRABAJO
Espacio ilustrativo · Se ejecuta en tu dispositivo
DISPOSITIVO PEQUEÑO. HERRAMIENTAS REALES.
Más que una ventana de chat.
La experiencia oficial
CLI y WebUI de la misma versión, con Agent, sesiones, Shell, LSP, MCP y complementos.
Todas las herramientas
Incluye curl, wget, Git, Node.js, Python, Rust y Clang. Amplía el entorno con apt.
Espacio para más
Alterna entre terminales, archivos y un escritorio XFCE opcional, compartiendo tus proyectos.
PRIMEROS PASOS
Tu próximo proyecto empieza aquí.
Leer la guía de instalación
Instala el APK ARM64 y prepara el entorno incluido.
Conecta tu proveedor en la nube o un modelo local compatible.
Abre tu espacio, escribe código, ejecuta comandos y compila.
VERSIÓN PRELIMINAR
La validación física se centra en Android 16 / K30 / 4 KiB. Continúan las pruebas de más dispositivos, estabilidad y auditoría.
Ver validación
Un port independiente y no oficial. GPL-3.0-or-later.
Todos los archivos
Informar de un problema
descargas de archivos
Tu descarga está lista.
El archivo se descargará desde GitHub. Si no comienza, pulsa el botón de abajo.
Volver al sitio`),
  'pt-BR': pack('Português', 'ltr', `
Pular para o conteúdo
Idioma
Idioma do sistema
Uma estação de desenvolvimento Android de código aberto
Seu espaço de criação.↵Agora no Android.
OpenCode oficial. Um ambiente Debian de verdade. Projetos, terminal e ferramentas juntos no seu celular.
Baixar APK
Atualizar OpenCode
Android 10+ · ARM64 · Sem root
Primeira instalação? Escolha o APK. O pacote atualiza juntos a interface e o servidor OpenCode correspondentes.
Copiar fonte de atualização assinada
Fonte de atualização copiada.
Selecione e copie a fonte abaixo.
UM ESPAÇO DE TRABALHO
Espaço ilustrativo · Executado no seu dispositivo
APARELHO PEQUENO. FERRAMENTAS REAIS.
Mais que uma janela de conversa.
A experiência oficial
CLI e WebUI da mesma versão, com Agent, sessões, Shell, LSP, MCP e plugins.
Todas as ferramentas
curl, wget, Git, Node.js, Python, Rust e Clang incluídos. Expanda com apt.
Espaço para ir além
Alterne entre terminais, arquivos e um desktop XFCE opcional, mantendo os mesmos projetos.
COMECE AQUI
Seu próximo projeto começa aqui.
Ler o guia de instalação
Instale o APK ARM64 e prepare o ambiente incluído.
Conecte seu provedor em nuvem ou um modelo local compatível.
Abra seu espaço, programe, execute comandos e compile.
PRÉVIA DE DESENVOLVIMENTO
A validação física se concentra em Android 16 / K30 / 4 KiB. Outros dispositivos, estabilidade e auditoria seguem em avaliação.
Ver validação
Um port independente e não oficial. GPL-3.0-or-later.
Todos os arquivos
Relatar um problema
downloads de arquivos
Seu download está pronto.
O arquivo será baixado do GitHub. Se não iniciar, use o botão abaixo.
Voltar ao site`),
  ru: pack('Русский', 'ltr', `
К содержимому
Язык
Язык системы
Открытая среда разработки для Android
Рабочая среда.↵Теперь на Android.
Официальный OpenCode и настоящая среда Debian. Проекты, терминал и инструменты на вашем телефоне.
Скачать APK
Обновить OpenCode
Android 10+ · ARM64 · Без root
Для первой установки выберите APK. Пакет обновления одновременно обновляет совместимые интерфейс и сервер OpenCode.
Копировать подписанный источник обновлений
Источник обновлений скопирован.
Выделите и скопируйте источник ниже.
ЕДИНОЕ РАБОЧЕЕ ПРОСТРАНСТВО
Пример рабочего пространства · Локально на устройстве
КОМПАКТНОЕ УСТРОЙСТВО. НАСТОЯЩИЕ ИНСТРУМЕНТЫ.
Больше, чем окно чата.
Официальные возможности
Совместимые CLI и WebUI с Agent, сессиями, Shell, LSP, MCP и плагинами.
Все нужные инструменты
Включены curl, wget, Git, Node.js, Python, Rust и Clang. Дополняйте среду через apt.
Больше пространства для работы
Переключайтесь между терминалами, файлами и дополнительным рабочим столом XFCE, используя одни проекты.
НАЧАЛО РАБОТЫ
Ваш следующий проект начинается здесь.
Руководство по установке
Установите ARM64 APK и подготовьте встроенную среду.
Подключите выбранный облачный сервис или совместимую локальную модель.
Откройте рабочее пространство, пишите код, выполняйте команды и собирайте проекты.
ПРЕДВАРИТЕЛЬНАЯ ВЕРСИЯ
Проверки на устройствах в основном охватывают Android 16 / K30 / 4 KiB. Другие устройства, стабильность и аудит ещё проверяются.
Результаты проверок
Независимый неофициальный порт. GPL-3.0-or-later.
Все файлы выпуска
Сообщить о проблеме
скачиваний файлов
Всё готово к скачиванию.
Файл загрузится с GitHub. Если загрузка не началась, нажмите кнопку ниже.
Вернуться на сайт`),
  ar: pack('العربية', 'rtl', `
انتقل إلى المحتوى
اللغة
لغة النظام
بيئة تطوير مفتوحة المصدر على Android
بيئة تطويرك.↵الآن على Android.
OpenCode الرسمي وبيئة Debian حقيقية. مشاريعك وطرفيتك وأدواتك معًا على هاتفك.
تنزيل APK
تحديث OpenCode
Android 10+ · ARM64 · دون root
للتثبيت الأول اختر APK. تحدّث الحزمة واجهة OpenCode وخادمها المتوافقين معًا.
نسخ مصدر التحديث الموقّع
تم نسخ مصدر التحديث.
حدّد مصدر التحديث أدناه وانسخه.
مساحة عمل واحدة
مساحة عمل توضيحية · تعمل محليًا على جهازك
جهاز صغير. أدوات حقيقية.
أكثر من نافذة محادثة.
التجربة الرسمية
CLI وWebUI متوافقان مع Agent والجلسات وShell وLSP وMCP والإضافات.
مجموعة أدوات متكاملة
تتضمن curl وwget وGit وNode.js وPython وRust وClang. وسّع البيئة باستخدام apt.
مساحة للمزيد
انتقل بين الطرفيات والملفات وسطح مكتب XFCE الاختياري مع مشاركة مشاريعك.
ابدأ الآن
مشروعك القادم يبدأ هنا.
اقرأ دليل التثبيت
ثبّت APK لمعمارية ARM64 وجهّز بيئة التشغيل المضمّنة.
اربط مزوّدك السحابي أو نموذجًا محليًا متوافقًا.
افتح مساحة عملك واكتب الشيفرة ونفّذ الأوامر وابنِ مشروعك.
نسخة هندسية تجريبية
تتركز الاختبارات الفعلية على Android 16 / K30 / 4 KiB. ما تزال اختبارات الأجهزة الأخرى والاستقرار والمراجعة قيد التنفيذ.
عرض سجل التحقق
نقل مستقل وغير رسمي. GPL-3.0-or-later.
جميع ملفات الإصدار
الإبلاغ عن مشكلة
تنزيلات الملفات
التنزيل جاهز.
سيُنزل الملف من GitHub. إن لم يبدأ التنزيل، استخدم الزر أدناه.
العودة إلى الموقع`),
  hi: pack('हिन्दी', 'ltr', `
मुख्य सामग्री पर जाएँ
भाषा
सिस्टम की भाषा
Android पर ओपन सोर्स विकास वातावरण
आपका विकास वातावरण।↵अब Android पर।
आधिकारिक OpenCode और वास्तविक Debian वातावरण। प्रोजेक्ट, टर्मिनल और विकास उपकरण, सब आपके फ़ोन पर।
APK डाउनलोड करें
OpenCode अपडेट
Android 10+ · ARM64 · root आवश्यक नहीं
पहली बार APK चुनें। अपडेट पैकेज संगत OpenCode फ़्रंटएंड और बैकएंड को एक साथ अपडेट करता है।
हस्ताक्षरित अपडेट स्रोत कॉपी करें
अपडेट स्रोत कॉपी हुआ।
नीचे का अपडेट स्रोत चुनकर कॉपी करें।
एक कार्यक्षेत्र
कार्यक्षेत्र का उदाहरण · आपके उपकरण पर स्थानीय रूप से चलता है
छोटा उपकरण। असली औज़ार।
केवल चैट विंडो से कहीं अधिक।
आधिकारिक अनुभव
संगत CLI और WebUI के साथ Agent, सत्र, Shell, LSP, MCP और प्लगइन।
सभी ज़रूरी विकास उपकरण
curl, wget, Git, Node.js, Python, Rust और Clang शामिल हैं। apt से और जोड़ें।
और काम के लिए जगह
टर्मिनल, फ़ाइलें और वैकल्पिक XFCE डेस्कटॉप के बीच बदलें। प्रोजेक्ट साझा रहते हैं।
शुरू करें
आपका अगला प्रोजेक्ट यहीं से शुरू होता है।
इंस्टॉलेशन गाइड पढ़ें
ARM64 APK इंस्टॉल करके शामिल रनटाइम तैयार करें।
अपनी चुनी हुई क्लाउड सेवा या संगत स्थानीय मॉडल जोड़ें।
कार्यक्षेत्र खोलें, कोड लिखें, कमांड चलाएँ और बिल्ड करें।
इंजीनियरिंग पूर्वावलोकन
वास्तविक उपकरण परीक्षण मुख्यतः Android 16 / K30 / 4 KiB पर हुए हैं। अन्य उपकरणों, स्थिरता और समीक्षा का काम जारी है।
परीक्षण रिकॉर्ड देखें
स्वतंत्र और अनौपचारिक पोर्ट। GPL-3.0-or-later।
रिलीज़ की सभी फ़ाइलें
समस्या बताएँ
फ़ाइल डाउनलोड
डाउनलोड तैयार है।
फ़ाइल GitHub से डाउनलोड होगी। शुरू न हो तो नीचे का बटन दबाएँ।
वेबसाइट पर लौटें`),
  id: pack('Bahasa Indonesia', 'ltr', `
Ke konten utama
Bahasa
Bahasa sistem
Ruang pengembangan Android sumber terbuka
Ruang pengembanganmu.↵Kini di Android.
OpenCode resmi dan lingkungan Debian nyata. Proyek, terminal, dan alat pengembangan dalam ponselmu.
Unduh APK
Pembaruan OpenCode
Android 10+ · ARM64 · Tanpa root
Instalasi pertama? Pilih APK. Paket pembaruan memperbarui antarmuka dan server OpenCode yang cocok secara bersamaan.
Salin sumber pembaruan bertanda tangan
Sumber pembaruan disalin.
Pilih dan salin sumber di bawah.
SATU RUANG KERJA
Ilustrasi ruang kerja · Berjalan lokal di perangkatmu
PERANGKAT KECIL. ALAT SUNGGUHAN.
Lebih dari jendela percakapan.
Pengalaman resmi
CLI dan WebUI yang cocok, dengan Agent, sesi, Shell, LSP, MCP, dan plugin.
Perangkat pengembangan lengkap
curl, wget, Git, Node.js, Python, Rust, dan Clang tersedia. Perluas dengan apt.
Ruang untuk lebih banyak hal
Beralih antara terminal, berkas, dan desktop XFCE opsional, dengan proyek yang sama.
MULAI
Proyek berikutnya dimulai di sini.
Baca panduan instalasi
Pasang APK ARM64 dan siapkan lingkungan bawaan.
Hubungkan penyedia cloud pilihanmu atau model lokal yang kompatibel.
Buka ruang kerja, tulis kode, jalankan perintah, dan bangun proyek.
PRATINJAU PENGEMBANGAN
Pengujian perangkat fisik terutama mencakup Android 16 / K30 / 4 KiB. Perangkat lain, stabilitas, dan audit masih diuji.
Lihat validasi
Port independen dan tidak resmi. GPL-3.0-or-later.
Semua berkas rilis
Laporkan masalah
unduhan berkas
Unduhanmu siap.
Berkas akan diunduh dari GitHub. Jika belum dimulai, gunakan tombol di bawah.
Kembali ke situs`),
  vi: pack('Tiếng Việt', 'ltr', `
Đến nội dung chính
Ngôn ngữ
Ngôn ngữ hệ thống
Trạm phát triển Android mã nguồn mở
Không gian lập trình.↵Nay trên Android.
OpenCode chính thức và môi trường Debian thực. Dự án, terminal và công cụ phát triển ngay trên điện thoại.
Tải APK
Cập nhật OpenCode
Android 10+ · ARM64 · Không cần root
Cài lần đầu? Chọn APK. Gói cập nhật nâng cấp đồng thời giao diện và máy chủ OpenCode tương ứng.
Sao chép nguồn cập nhật đã ký
Đã sao chép nguồn cập nhật.
Chọn và sao chép nguồn cập nhật bên dưới.
MỘT KHÔNG GIAN LÀM VIỆC
Minh họa không gian làm việc · Chạy cục bộ trên thiết bị
THIẾT BỊ NHỎ. CÔNG CỤ THẬT.
Không chỉ là cửa sổ trò chuyện.
Trải nghiệm chính thức
CLI và WebUI tương ứng với Agent, phiên làm việc, Shell, LSP, MCP và plugin.
Bộ công cụ đầy đủ
Có sẵn curl, wget, Git, Node.js, Python, Rust và Clang. Mở rộng thêm bằng apt.
Không gian cho nhiều việc hơn
Chuyển giữa terminal, tệp và màn hình XFCE tùy chọn, cùng chia sẻ dự án.
BẮT ĐẦU
Dự án tiếp theo bắt đầu tại đây.
Đọc hướng dẫn cài đặt
Cài APK ARM64 và chuẩn bị môi trường đi kèm.
Kết nối dịch vụ đám mây bạn chọn hoặc mô hình cục bộ tương thích.
Mở không gian làm việc, viết mã, chạy lệnh và biên dịch.
BẢN XEM TRƯỚC KỸ THUẬT
Kiểm thử thiết bị thực chủ yếu trên Android 16 / K30 / 4 KiB. Các thiết bị khác, độ ổn định và kiểm toán vẫn đang được xác minh.
Xem kết quả kiểm thử
Bản chuyển thể độc lập, không chính thức. GPL-3.0-or-later.
Tất cả tệp phát hành
Báo lỗi
lượt tải tệp
Tệp tải đã sẵn sàng.
Tệp sẽ được tải từ GitHub. Nếu chưa bắt đầu, hãy dùng nút bên dưới.
Quay lại trang web`),
  tr: pack('Türkçe', 'ltr', `
İçeriğe geç
Dil
Sistem dili
Açık kaynaklı Android geliştirme ortamı
Geliştirme ortamınız.↵Artık Android’de.
Resmî OpenCode ve gerçek Debian ortamı. Projeleriniz, terminaliniz ve araçlarınız telefonunuzda bir arada.
APK indir
OpenCode güncellemesi
Android 10+ · ARM64 · Root gerekmez
İlk kurulumda APK’yi seçin. Güncelleme paketi uyumlu OpenCode arayüzünü ve sunucusunu birlikte günceller.
İmzalı güncelleme kaynağını kopyala
Güncelleme kaynağı kopyalandı.
Aşağıdaki güncelleme kaynağını seçip kopyalayın.
TEK ÇALIŞMA ALANI
Örnek çalışma alanı · Cihazınızda yerel çalışır
KÜÇÜK CİHAZ. GERÇEK ARAÇLAR.
Bir sohbet penceresinden fazlası.
Resmî deneyim
Agent, oturumlar, Shell, LSP, MCP ve eklentilerle uyumlu üst kaynak CLI ve WebUI.
Eksiksiz araç seti
curl, wget, Git, Node.js, Python, Rust ve Clang dahildir. apt ile genişletin.
Daha fazlası için alan
Terminaller, dosyalar ve isteğe bağlı XFCE masaüstü arasında aynı projelerle geçiş yapın.
BAŞLAYIN
Sıradaki projeniz burada başlar.
Kurulum kılavuzunu okuyun
ARM64 APK’yi kurup yerleşik çalışma ortamını hazırlayın.
Seçtiğiniz bulut sağlayıcısını veya uyumlu yerel modeli bağlayın.
Çalışma alanını açın, kod yazın, komut çalıştırın ve derleyin.
GELİŞTİRME ÖNİZLEMESİ
Fiziksel testler ağırlıklı olarak Android 16 / K30 / 4 KiB üzerinde yapılıyor. Diğer cihazlar, kararlılık ve denetim çalışmaları sürüyor.
Doğrulama kaydını görün
Bağımsız, resmî olmayan bir port. GPL-3.0-or-later.
Tüm sürüm dosyaları
Sorun bildirin
dosya indirmesi
İndirmeniz hazır.
Dosya GitHub’dan indirilecek. Başlamazsa aşağıdaki düğmeyi kullanın.
Siteye dön`),
  uk: pack('Українська', 'ltr', `
До вмісту
Мова
Мова системи
Відкрите середовище розробки для Android
Ваш робочий простір.↵Тепер на Android.
Офіційний OpenCode і справжнє середовище Debian. Проєкти, термінал та інструменти на вашому телефоні.
Завантажити APK
Оновлення OpenCode
Android 10+ · ARM64 · Без root
Для першого встановлення виберіть APK. Пакет оновлює разом сумісні інтерфейс і сервер OpenCode.
Копіювати підписане джерело оновлень
Джерело оновлень скопійовано.
Виберіть і скопіюйте джерело нижче.
ЄДИНИЙ РОБОЧИЙ ПРОСТІР
Приклад робочого простору · Працює локально на пристрої
МАЛИЙ ПРИСТРІЙ. СПРАВЖНІ ІНСТРУМЕНТИ.
Більше, ніж вікно чату.
Офіційний досвід
Сумісні CLI та WebUI з Agent, сесіями, Shell, LSP, MCP і плагінами.
Повний набір інструментів
Включено curl, wget, Git, Node.js, Python, Rust і Clang. Розширюйте через apt.
Простір для більшого
Перемикайтеся між терміналами, файлами й додатковим робочим столом XFCE, працюючи зі спільними проєктами.
ПОЧАТОК РОБОТИ
Ваш наступний проєкт починається тут.
Посібник зі встановлення
Встановіть ARM64 APK і підготуйте вбудоване середовище.
Підключіть обраний хмарний сервіс або сумісну локальну модель.
Відкрийте робочий простір, пишіть код, виконуйте команди та збирайте проєкти.
ІНЖЕНЕРНИЙ ПОПЕРЕДНІЙ ВИПУСК
Фізичні перевірки переважно охоплюють Android 16 / K30 / 4 KiB. Інші пристрої, стабільність та аудит ще перевіряються.
Переглянути перевірки
Незалежний неофіційний порт. GPL-3.0-or-later.
Усі файли випуску
Повідомити про проблему
завантажень файлів
Завантаження готове.
Файл буде завантажено з GitHub. Якщо не почалося, натисніть кнопку нижче.
Повернутися на сайт`),
};
