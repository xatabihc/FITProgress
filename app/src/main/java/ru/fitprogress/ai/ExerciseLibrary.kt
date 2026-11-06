package ru.fitprogress.ai

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** Immutable offline snapshot. Source descriptions remain labelled as English source text. */
object OpenExerciseLibrary {
    const val SOURCE = "yuhonas/free-exercise-db"
    const val REVISION = "f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5"
    const val LICENSE = "Unlicense"
    private data class RussianGuide(val technique: String, val errors: String, val alternatives: String)
    private val russianGuides = mapOf(
        "Close-Grip_Barbell_Bench_Press" to RussianGuide("Лягте на скамью, установите стопы устойчиво и возьмитесь за гриф хватом примерно на ширине плеч. Удерживая локти ближе к корпусу, плавно опустите гриф к груди и выжмите вверх без рывка.", "Не беритесь за гриф чрезмерно узко, не отбивайте его от груди и не теряйте контроль запястий.", "Жим гантелей нейтральным хватом или отжимания узким хватом. Используйте страховочные упоры или партнёра при тяжёлом весе."),
        "Dumbbell_Lunges" to RussianGuide("Встаньте прямо с гантелями вдоль тела. Сделайте комфортный шаг вперёд, согните обе ноги, сохраняя равновесие и направление колена по линии стопы, затем оттолкнитесь передней ногой и вернитесь в исходное положение.", "Не заваливайте колено внутрь, не теряйте равновесие и не сокращайте амплитуду за счёт рывков.", "Выпады без веса, обратные выпады или сплит-приседания с опорой."),
        "Cable_Crunch" to RussianGuide("Закрепите канат на верхнем блоке и встаньте на колени лицом к тренажёру. Держите концы каната рядом с головой, напрягите мышцы живота и плавно округлите верхнюю часть корпуса, направляя рёбра к тазу. Вернитесь под контролем.", "Не тяните канат руками, не раскачивайте таз и не используйте слишком большой вес.", "Скручивания на коврике или скручивания в тренажёре."),
        "Barbell_Glute_Bridge" to RussianGuide("Сядьте на пол с мягкой накладкой на грифе, затем расположите штангу над тазом и лягте на спину. Согните колени, упритесь стопами в пол и поднимите таз, напрягая ягодицы. Опускайтесь плавно.", "Не давите грифом на выступающие кости таза, не переразгибайте поясницу и не толкайтесь носками.", "Ягодичный мост без веса или с гантелью."),
        "Cable_Chest_Press" to RussianGuide("Настройте рукояти и сопротивление блочного тренажёра. Удерживая корпус устойчиво и лопатки контролируемо, выжмите рукояти вперёд до комфортного разгибания рук, затем медленно верните их назад.", "Не поднимайте плечи к ушам, не бросайте вес назад и не допускайте болезненного переразгибания плеч.", "Жим гантелей лёжа или отжимания от опоры."),
        "Dumbbell_Floor_Press" to RussianGuide("Лягте на пол, согните колени и удерживайте гантели над грудью. Медленно опустите локти до мягкого касания пола, остановитесь и выжмите гантели вверх, сохраняя положение запястий.", "Не ударяйте локтями о пол, не разводите их слишком широко и не теряйте контроль гантелей.", "Жим гантелей на скамье или отжимания от пола."),
        "Barbell_Shrug" to RussianGuide("Встаньте прямо, держите штангу перед бёдрами прямым хватом. Плавно поднимите плечи вверх, ненадолго задержитесь и опустите их, не меняя положение корпуса.", "Не вращайте плечи кругами, не сгибайте локти для подъёма грифа и не помогайте себе раскачиванием.", "Шраги с гантелями или на тренажёре."),
        "Dumbbell_Bicep_Curl" to RussianGuide("Встаньте устойчиво, опустите руки с гантелями вдоль тела. Удерживая плечи почти неподвижными, согните локти и поднимите гантели, затем медленно верните их вниз.", "Не отклоняйте корпус назад, не подбрасывайте вес и не уводите локти далеко вперёд.", "Попеременные сгибания рук с гантелями или сгибания на нижнем блоке."),
        "Cable_Rope_Overhead_Triceps_Extension" to RussianGuide("Прикрепите канат к нижнему блоку. Поднимите руки над головой, удерживайте локти рядом с головой и медленно согните их, опуская канат назад. Разогните руки без рывка, не меняя положение корпуса.", "Не разводите локти чрезмерно, не прогибайте поясницу и не работайте через боль в плечах.", "Разгибание рук с одной гантелью из-за головы или разгибание на верхнем блоке."),
        "Barbell_Seated_Calf_Raise" to RussianGuide("Сядьте на устойчивую скамью, расположите переднюю часть стоп на невысокой платформе и защитите бёдра мягкой накладкой под грифом. Поднимите пятки за счёт мышц голени, затем плавно опустите их в комфортную глубину.", "Не подпрыгивайте пятками, не допускайте скольжения штанги и не используйте неустойчивую опору.", "Подъёмы на носки сидя с гантелями или на специальном тренажёре."),
        "Pushups" to RussianGuide("Примите упор лёжа: ладони примерно под плечами, тело удерживайте прямой линией. Согните руки, опуская грудь под контролем; оттолкнитесь и выпрямите руки без резкого запирания локтей.", "Не прогибайте поясницу, не поднимайте таз и не разводите локти строго в стороны.", "Отжимания от высокой опоры или с колен; усложнение — узкая постановка рук."),
        "Pullups" to RussianGuide("Возьмитесь за перекладину устойчивым хватом. Начните движение сведением лопаток, подтяните грудь к перекладине без раскачивания и плавно опуститесь до комфортного разгибания рук.", "Не раскачивайтесь, не вытягивайте подбородок вместо подъёма и не падайте в нижнюю точку.", "Тяга верхнего блока или подтягивания с резиновой поддержкой."),
        "Barbell_Bench_Press_-_Medium_Grip" to RussianGuide("Лягте так, чтобы гриф находился над глазами; стопы устойчиво стоят на полу. Сведите лопатки, снимите гриф, опустите его к нижней части груди с предплечьями примерно вертикально и выжмите вверх.", "Не отбивайте гриф от груди, не теряйте опору стоп и не допускайте неконтролируемого разведения локтей.", "Жим гантелей лёжа или отжимания. Для тяжёлых подходов используйте страховочные упоры или партнёра."),
        "Dumbbell_Bench_Press" to RussianGuide("Лягте на скамью, удерживая гантели над грудью. Опускайте их по сторонам груди в контролируемой амплитуде, сохраняя запястья над локтями; выжмите вверх без столкновения гантелей.", "Не переразгибайте поясницу, не опускайте плечо в болезненную глубину и не сводите гантели ударом.", "Жим штанги или отжимания от пола."),
        "Barbell_Full_Squat" to RussianGuide("Поставьте стопы устойчиво, направив носки по естественному углу. Отведите таз и согните колени, удерживая всю стопу на полу и корпус собранным; встаньте, одновременно разгибая таз и колени.", "Не теряйте равновесие на носках, не округляйте поясницу и не форсируйте глубину, которую не контролируете.", "Приседание к коробке, приседание с собственным весом или жим ногами."),
        "Barbell_Deadlift" to RussianGuide("Подойдите к грифу так, чтобы он был над серединой стопы. Зафиксируйте корпус, возьмитесь за гриф и поднимайте его близко к ногам, разгибая колени и таз согласованно; опускайте по той же траектории.", "Не округляйте спину под нагрузкой, не отводите гриф от ног и не дёргайте его с пола.", "Тяга гири с возвышения или румынская тяга с лёгким весом."),
        "Bent_Over_Barbell_Row" to RussianGuide("Удерживая корпус наклонённым и спину нейтральной, подтяните гриф к нижней части живота. Кратко сведите лопатки и опустите гриф под контролем.", "Не поднимайте корпус на каждом повторе и не округляйте поясницу.", "Тяга гантели с опорой или тяга горизонтального блока."),
        "Plank" to RussianGuide("Опирайтесь на предплечья и носки, локти расположите под плечами. Напрягите живот и ягодицы, сохраняя прямую линию от головы до пяток; дышите ровно и завершите подход до потери положения.", "Не провисайте в пояснице, не поднимайте таз слишком высоко и не задерживайте дыхание.", "Планка с колен или короткие удержания у стены."),
        "Barbell_Curl" to RussianGuide("Встаньте устойчиво, держите гриф на вытянутых руках. Согните локти, поднимая гриф без движения плеч; плавно опустите до исходного положения.", "Не раскачивайте корпус, не уводите локти вперёд и не разгибайте локоть рывком.", "Сгибание рук с гантелями или на нижнем блоке."),
        "Standing_Military_Press" to RussianGuide("Поставьте стопы устойчиво и напрягите корпус. Начните с грифа у верхней части груди, выжмите его над головой по близкой к лицу траектории и верните под контролем.", "Не переразгибайте поясницу и не продолжайте через боль в плече.", "Жим гантелей сидя с лёгким весом или жим landmine."),
        "Romanian_Deadlift" to RussianGuide("Держите вес перед бёдрами. Отведите таз назад с мягко согнутыми коленями, опуская вес вдоль ног до выраженного, но контролируемого натяжения задней поверхности бедра; вернитесь за счёт разгибания таза.", "Не превращайте движение в присед и не округляйте спину ради большей глубины.", "Наклон-тазовый шарнир без веса или тяга гири с возвышения."),
        "Full_Range-Of-Motion_Lat_Pulldown" to RussianGuide("Сядьте, зафиксируйте бёдра под упорами и возьмитесь за рукоять. Опустите плечи, затем потяните рукоять к верхней части груди и медленно верните вверх.", "Не отклоняйтесь далеко назад, не тяните за голову и не бросайте вес в верхней точке.", "Подтягивания с резиной или тяга блока нейтральным хватом."),
        "Leg_Press" to RussianGuide("Расположите стопы на платформе так, чтобы колени двигались по направлению носков. Опускайте платформу до глубины, при которой таз и поясница остаются прижатыми; выжмите, не запирая колени.", "Не отрывайте таз от спинки и не сводите колени внутрь.", "Приседание к опоре или сплит-присед без веса."),
        "Lying_Leg_Curls" to RussianGuide("Настройте валик чуть выше пяток и лягте ровно. Согните колени, подтягивая валик к бёдрам, затем медленно разогните ноги.", "Не поднимайте таз и не бросайте валик в нижней точке.", "Сгибание ног сидя или ягодичный мост с шагами.")
    )
    @Volatile private var cache: List<Exercise>? = null
    private val russianNames = mapOf(
        "Barbell_Curls_Lying_Against_An_Incline" to "Сгибание рук со штангой лёжа на наклонной скамье",
        "Barbell_Guillotine_Bench_Press" to "Жим штанги к шее лёжа",
        "Barbell_Incline_Shoulder_Raise" to "Подъём плеч со штангой на наклонной скамье",
        "Barbell_Rollout_from_Bench" to "Выкатывание со штангой со скамьи",
        "Barbell_Side_Bend" to "Наклоны в сторону со штангой",
        "Barbell_Side_Split_Squat" to "Боковые выпады со штангой",
        "Dumbbell_Clean" to "Взятие гантелей на грудь",
        "Dumbbell_Incline_Row" to "Тяга гантелей на наклонной скамье",
        "Dumbbell_Incline_Shoulder_Raise" to "Подъём плеч с гантелями на наклонной скамье",
        "Dumbbell_Lying_One-Arm_Rear_Lateral_Raise" to "Разведение одной руки с гантелью лёжа для задней дельты",
        "Dumbbell_Lying_Pronation" to "Пронация предплечья с гантелью лёжа",
        "Dumbbell_Lying_Rear_Lateral_Raise" to "Разведение гантелей лёжа для задних дельт",
        "Dumbbell_Lying_Supination" to "Супинация предплечья с гантелью лёжа",
        "Dumbbell_One-Arm_Upright_Row" to "Тяга одной гантели к подбородку",
        "Dumbbell_Prone_Incline_Curl" to "Сгибание рук с гантелями лёжа животом на наклонной скамье",
        "Dumbbell_Raise" to "Подъём гантелей",
        "Dumbbell_Rear_Lunge" to "Выпады назад с гантелями",
        "Dumbbell_Scaption" to "Подъём гантелей в плоскости лопатки",
        "Dumbbell_Seated_Box_Jump" to "Прыжок на тумбу из положения сидя с гантелями",
        "Dumbbell_Seated_One-Leg_Calf_Raise" to "Подъём на носок одной ноги сидя с гантелью",
        "Dumbbell_Shoulder_Press" to "Жим гантелей над головой",
        "Dumbbell_Shrug" to "Шраги с гантелями",
        "Dumbbell_Side_Bend" to "Наклоны в сторону с гантелью",
        "Dumbbell_Squat_To_A_Bench" to "Приседания с гантелями до скамьи",
        "Dumbbell_Step_Ups" to "Зашагивания на платформу с гантелями",
        "Dumbbell_Tricep_Extension_-Pronated_Grip" to "Разгибание рук с гантелями прямым хватом",
        "Pull_Through" to "Тяга нижнего блока между ног",
        "Push-Up_Wide" to "Отжимания с широкой постановкой рук",
        "Push-Ups_-_Close_Triceps_Position" to "Отжимания с узкой постановкой рук для трицепса",
        "Push-Ups_With_Feet_Elevated" to "Отжимания с ногами на возвышении",
        "Push-Ups_With_Feet_On_An_Exercise_Ball" to "Отжимания с ногами на фитболе",
        "Push_Press" to "Жимовой швунг",
        "Push_Press_-_Behind_the_Neck" to "Жимовой швунг из-за головы",
        "Push_Up_to_Side_Plank" to "Отжимание с переходом в боковую планку",
        "Pushups_Close_and_Wide_Hand_Positions" to "Отжимания с чередованием узкой и широкой постановки рук",
        "Seated_Band_Hamstring_Curl" to "Сгибание ног сидя с эспандером",
        "Seated_Barbell_Military_Press" to "Армейский жим штанги сидя",
        "Seated_Barbell_Twist" to "Повороты корпуса сидя со штангой",
        "Seated_Bent-Over_One-Arm_Dumbbell_Triceps_Extension" to "Разгибание одной руки с гантелью в наклоне сидя",
        "Seated_Bent-Over_Rear_Delt_Raise" to "Разведение рук в наклоне сидя для задних дельт",
        "Seated_Bent-Over_Two-Arm_Dumbbell_Triceps_Extension" to "Разгибание обеих рук с гантелями в наклоне сидя",
        "Seated_Cable_Shoulder_Press" to "Жим на плечи сидя на блочном тренажёре",
        "Seated_Calf_Stretch" to "Растяжка икр сидя",
        "Seated_Close-Grip_Concentration_Barbell_Curl" to "Концентрированное сгибание рук со штангой узким хватом сидя",
        "Seated_Dumbbell_Curl" to "Сгибание рук с гантелями сидя",
        "Seated_Dumbbell_Inner_Biceps_Curl" to "Сгибание рук с гантелями сидя с разворотом наружу",
        "Seated_Dumbbell_Palms-Down_Wrist_Curl" to "Разгибание запястий с гантелями сидя",
        "Seated_Dumbbell_Palms-Up_Wrist_Curl" to "Сгибание запястий с гантелями сидя",
        "Seated_Dumbbell_Press" to "Жим гантелей сидя",
        "Seated_Flat_Bench_Leg_Pull-In" to "Подтягивание коленей сидя на горизонтальной скамье",
        "Seated_Floor_Hamstring_Stretch" to "Растяжка задней поверхности бедра сидя на полу",
        "Seated_Good_Mornings" to "Наклоны со штангой сидя",
        "Seated_Hamstring_and_Calf_Stretch" to "Растяжка задней поверхности бедра и икр сидя",
        "Seated_Head_Harness_Neck_Resistance" to "Упражнение для шеи с лямкой и отягощением сидя",
        "Seated_Leg_Curl" to "Сгибание ног в тренажёре сидя",
        "Seated_Leg_Tucks" to "Подтягивание коленей сидя",
        "Seated_One-Arm_Dumbbell_Palms-Down_Wrist_Curl" to "Разгибание одного запястья с гантелью сидя",
        "Seated_One-Arm_Dumbbell_Palms-Up_Wrist_Curl" to "Сгибание одного запястья с гантелью сидя",
        "Seated_One-arm_Cable_Pulley_Rows" to "Тяга нижнего блока одной рукой сидя",
        "Seated_Overhead_Stretch" to "Растяжка с руками над головой сидя",
        "Seated_Palm-Up_Barbell_Wrist_Curl" to "Сгибание запястий со штангой сидя",
        "Seated_Palms-Down_Barbell_Wrist_Curl" to "Разгибание запястий со штангой сидя",
        "Seated_Side_Lateral_Raise" to "Подъём гантелей в стороны сидя",
        "Seated_Triceps_Press" to "Разгибание рук с гантелью из-за головы сидя",
        "Seated_Two-Arm_Palms-Up_Low-Pulley_Wrist_Curl" to "Сгибание запястий на нижнем блоке сидя",
        "Barbell_Ab_Rollout" to "Выкатывание со штангой для пресса",
        "Barbell_Ab_Rollout_-_On_Knees" to "Выкатывание со штангой с колен",
        "Barbell_Glute_Bridge" to "Ягодичный мост со штангой",
        "Barbell_Hack_Squat" to "Гакк-приседание со штангой",
        "Barbell_Hip_Thrust" to "Подъём таза со штангой на скамье",
        "Barbell_Incline_Bench_Press_-_Medium_Grip" to "Жим штанги на наклонной скамье средним хватом",
        "Barbell_Lunge" to "Выпады со штангой",
        "Barbell_Rear_Delt_Row" to "Тяга штанги на задние дельты",
        "Barbell_Seated_Calf_Raise" to "Подъёмы на носки сидя со штангой",
        "Barbell_Shoulder_Press" to "Жим штанги над головой",
        "Barbell_Shrug" to "Шраги со штангой",
        "Barbell_Shrug_Behind_The_Back" to "Шраги со штангой за спиной",
        "Barbell_Squat_To_A_Bench" to "Приседания со штангой до скамьи",
        "Barbell_Step_Ups" to "Зашагивания на платформу со штангой",
        "Barbell_Walking_Lunge" to "Выпады со штангой в ходьбе",
        "Cable_Chest_Press" to "Жим от груди на блочном тренажёре",
        "Cable_Crunch" to "Скручивания на верхнем блоке",
        "Cable_Deadlifts" to "Становая тяга на блочном тренажёре",
        "Cable_Hammer_Curls_-_Rope_Attachment" to "Молотковые сгибания рук с канатной рукоятью",
        "Cable_Hip_Adduction" to "Приведение ноги на нижнем блоке",
        "Cable_Incline_Triceps_Extension" to "Разгибание рук на блоке с наклоном",
        "Cable_Lying_Triceps_Extension" to "Разгибание рук лёжа на блоке",
        "Cable_One_Arm_Tricep_Extension" to "Разгибание одной руки на блоке",
        "Cable_Preacher_Curl" to "Сгибание рук на скамье Скотта с блоком",
        "Cable_Rear_Delt_Fly" to "Разведение рук на блоках для задних дельт",
        "Cable_Rope_Overhead_Triceps_Extension" to "Разгибание рук с канатом из-за головы",
        "Cable_Seated_Lateral_Raise" to "Подъём рук в стороны сидя на блоке",
        "Cable_Shoulder_Press" to "Жим на плечи в блочном тренажёре",
        "Cable_Wrist_Curl" to "Сгибание запястий на блоке",
        "Close-Grip_Barbell_Bench_Press" to "Жим штанги лёжа узким хватом",
        "Decline_Barbell_Bench_Press" to "Жим штанги на скамье с отрицательным наклоном",
        "Decline_Dumbbell_Bench_Press" to "Жим гантелей на скамье с отрицательным наклоном",
        "Dumbbell_Alternate_Bicep_Curl" to "Попеременные сгибания рук с гантелями",
        "Dumbbell_Bench_Press_with_Neutral_Grip" to "Жим гантелей лёжа нейтральным хватом",
        "Dumbbell_Bicep_Curl" to "Сгибание рук с гантелями",
        "Dumbbell_Floor_Press" to "Жим гантелей лёжа на полу",
        "Dumbbell_Lunges" to "Выпады с гантелями",
        "Dumbbell_One-Arm_Shoulder_Press" to "Жим гантели одной рукой над головой",
        "Dumbbell_One-Arm_Triceps_Extension" to "Разгибание руки с гантелью из-за головы",
        "3_4_Sit-Up" to "Подъёмы корпуса на 3/4", "90_90_Hamstring" to "Растяжка задней поверхности бедра 90/90",
        "Ab_Crunch_Machine" to "Скручивания в тренажёре", "Ab_Roller" to "Выкатывание ролика для пресса",
        "Air_Bike" to "Воздушный велотренажёр", "Alternate_Incline_Dumbbell_Curl" to "Попеременные сгибания гантелей на наклонной скамье",
        "Barbell_Bench_Press_-_Medium_Grip" to "Жим штанги лёжа средним хватом", "Barbell_Full_Squat" to "Приседание со штангой",
        "Barbell_Deadlift" to "Становая тяга со штангой", "Barbell_Row" to "Тяга штанги в наклоне",
        "Bent_Over_Barbell_Row" to "Тяга штанги в наклоне", "Dumbbell_Bench_Press" to "Жим гантелей лёжа",
        "Dumbbell_Flyes" to "Разведение гантелей лёжа", "Dumbbell_Row" to "Тяга гантели одной рукой",
        "Dumbbell_Squat" to "Приседание с гантелями", "Pullups" to "Подтягивания",
        "Pushups" to "Отжимания", "Push-Up" to "Отжимания", "Plank" to "Планка",
        "Barbell_Curl" to "Сгибание рук со штангой", "Standing_Calf_Raises" to "Подъёмы на носки стоя",
        "Romanian_Deadlift" to "Румынская тяга", "Front_Squat" to "Фронтальное приседание",
        "Overhead_Press" to "Жим штанги над головой", "Military_Press" to "Армейский жим",
        "Lat_Pulldown" to "Тяга верхнего блока", "Seated_Cable_Rows" to "Тяга блока сидя",
        "Dips_-_Triceps_Version" to "Отжимания на брусьях с акцентом на трицепс",
        "Cable_Crossover" to "Сведение рук в кроссовере", "Leg_Press" to "Жим ногами в тренажёре",
        "Leg_Extensions" to "Разгибание ног в тренажёре", "Lying_Leg_Curls" to "Сгибание ног лёжа",
        "Hip_Thrust" to "Ягодичный мост со штангой", "Walking_Lunge" to "Выпады в ходьбе",
        "Jumping_Jacks" to "Прыжки с разведением рук и ног", "Burpee" to "Берпи", "Burpees" to "Берпи"
    )
    private val muscles = mapOf(
        "chest" to "грудные мышцы", "middle back" to "средняя часть спины", "lower back" to "поясница", "lats" to "широчайшие",
        "shoulders" to "плечи", "biceps" to "бицепс", "triceps" to "трицепс", "forearms" to "предплечья", "abdominals" to "пресс",
        "quadriceps" to "квадрицепсы", "hamstrings" to "задняя поверхность бедра", "glutes" to "ягодичные мышцы", "calves" to "икры",
        "adductors" to "приводящие мышцы", "abductors" to "отводящие мышцы", "neck" to "мышцы шеи"
    )
    private val gear = mapOf(
        "body only" to "без оборудования", "dumbbell" to "гантели", "barbell" to "штанга", "kettlebells" to "гири",
        "machine" to "тренажёр", "cable" to "блочный тренажёр", "bands" to "эспандер", "band" to "эспандер",
        "foam roll" to "ролл для массажа", "medicine ball" to "медбол", "exercise ball" to "фитбол", "e-z curl bar" to "EZ-гриф",
        "other" to "другое оборудование", "none" to "без оборудования"
    )
    private fun text(o: JsonObject, key: String) = o.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun array(o: JsonObject, key: String) = o.getAsJsonArray(key)?.mapNotNull { if(it.isJsonNull) null else it.asString }.orEmpty()
    private fun level(value: String) = when(value.lowercase(Locale.ROOT)) { "beginner" -> "Начинающий"; "intermediate" -> "Средний"; "expert", "advanced" -> "Продвинутый"; else -> "Не указан источником" }
    private fun category(primary: List<String>, category: String): String {
        if(category.equals("cardio", true)) return "Кардио"
        if(category.equals("stretching", true)) return "Растяжка"
        val muscle = primary.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
        return when(muscle) {
            "chest", "pectorals" -> "Грудь"
            "lats", "middle back" -> "Широчайшие и верх спины"
            "lower back" -> "Нижняя часть спины"
            "shoulders" -> "Плечи"
            "biceps" -> "Бицепс"
            "triceps" -> "Трицепс"
            "forearms" -> "Предплечья"
            "abdominals" -> "Пресс"
            "quadriceps" -> "Квадрицепсы"
            "hamstrings" -> "Задняя поверхность бедра"
            "glutes" -> "Ягодицы"
            "calves" -> "Икры"
            "adductors" -> "Ноги"
            else -> "Всё тело"
        }
    }
    fun sourceInfo(id: String): ExerciseSourceInfo? = ExerciseSourceMetadata.get(id)
    fun entries(context: Context): List<Exercise> {
        cache?.let { return it }
        return synchronized(this) {
            cache?.let { return it }
            val root = JsonParser.parseReader(context.assets.open("exercises-open.json").bufferedReader()).asJsonObject
            require(text(root, "revision") == REVISION && text(root, "license") == LICENSE)
            val rows = root.getAsJsonArray("exercises").mapNotNull { node -> runCatching {
                val row = node.asJsonObject
                val sourceId = text(row, "id")
                val english = text(row, "name").trim()
                val instructionList = array(row, "instructions").map(String::trim).filter(String::isNotBlank)
                val primary = array(row, "primaryMuscles")
                if(sourceId.isBlank() || english.isBlank() || instructionList.isEmpty() || primary.isEmpty()) return@runCatching null
                val id = "open-$sourceId"
                val gearEn = text(row, "equipment").ifBlank { "unspecified" }
                val primaryRu = primary.map { muscles[it.lowercase(Locale.ROOT)] ?: it }
                val secondaryRu = array(row, "secondaryMuscles").map { muscles[it.lowercase(Locale.ROOT)] ?: it }
                val category = category(primary, text(row, "category"))
                val originalGear = text(row, "equipment")
                val englishMuscles = primary + array(row, "secondaryMuscles")
                val guide = russianGuides[sourceId]
                ExerciseSourceMetadata.put(id, ExerciseSourceInfo(level(text(row, "level")), text(row, "category"), english, listOf(sourceId.replace('_', ' '), sourceId.replace('_', ' ').replace('-', ' '), english, originalGear) + englishMuscles,
                    primaryMuscles = primaryRu.joinToString(", "), secondaryMuscles = secondaryRu.joinToString(", "), commonErrors = guide?.errors.orEmpty(), alternatives = guide?.alternatives.orEmpty(), localizedGuide = guide != null))
                Exercise(id = id, name = russianNames[sourceId] ?: english, category = category,
                    technique = guide?.technique ?: instructionList.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n"),
                    muscles = (primaryRu + secondaryRu).distinct().joinToString(", "),
                    equipment = if(originalGear.isBlank() || gearEn == "unspecified") "Не указано источником" else gear[gearEn.lowercase(Locale.ROOT)] ?: gearEn.replaceFirstChar { it.uppercase() },
                    sets = 3, reps = 10, seconds = 0, rest = 60,
                    notes = "Открытая запись: ${level(text(row, "level"))} · ${if(guide == null) "исходная инструкция на английском" else "краткая русская редакция FitProgressAI"}. Это общая справка, не медицинская рекомендация.", builtIn = true)
            }.getOrNull() }
            require(rows.map { it.id }.distinct().size == rows.size) { "Exercise dataset has duplicate identifiers" }
            Log.i("FitExerciseLibrary", "Loaded ${rows.size} unique offline exercises from $SOURCE@$REVISION")
            rows.also { cache = it }
        }
    }
}

