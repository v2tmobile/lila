package lila.game

import chess.format.Forsyth
import chess.format.pgn.{ Pgn, Tag, TagType, Parser, ParsedPgn }
import chess.format.{ pgn => chessPgn }
import chess.{ Centis, Color }
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

import lila.common.LightUser

final class PgnDump(
    netBaseUrl: String,
    getLightUser: LightUser.GetterSync
) {

  import PgnDump._

  def apply(game: Game, initialFen: Option[String], flags: WithFlags): Pgn = {
    val imported = game.pgnImport.flatMap { pgni =>
      Parser.full(pgni.pgn).toOption
    }
    val ts = tags(game, initialFen, imported)
    val fenSituation = ts find (_.name == Tag.FEN) flatMap { case Tag(_, fen) => Forsyth <<< fen }
    val moves2 = fenSituation.??(_.situation.color.black).fold(".." +: game.pgnMoves, game.pgnMoves)
    val turns = makeTurns(
      moves2,
      fenSituation.map(_.fullMoveNumber) | 1,
      flags.clocks ?? ~game.bothClockStates,
      game.startColor
    )
    Pgn(ts, turns)
  }

  private val fileR = """[\s,]""".r

  def filename(game: Game): String = gameLightUsers(game) match {
    case (wu, bu) => fileR.replaceAllIn(
      "lichess_pgn_%s_%s_vs_%s.%s.pgn".format(
        Tag.UTCDate.format.print(game.createdAt),
        player(game.whitePlayer, wu),
        player(game.blackPlayer, bu),
        game.id
      ), "_"
    )
  }

  private def gameUrl(id: String) = s"$netBaseUrl/$id"

  private def gameLightUsers(game: Game): (Option[LightUser], Option[LightUser]) =
    (game.whitePlayer.userId ?? getLightUser) -> (game.blackPlayer.userId ?? getLightUser)

  private def rating(p: Player) = p.rating.fold("?")(_.toString)

  private def player(p: Player, u: Option[LightUser]) =
    p.aiLevel.fold(u.fold(p.name | lila.user.User.anonymous)(_.name))("lichess AI level " + _)

  private val customStartPosition: Set[chess.variant.Variant] =
    Set(chess.variant.Chess960, chess.variant.FromPosition, chess.variant.Horde, chess.variant.RacingKings)

  private def eventOf(game: Game) = {
    val perf = game.perfType.fold("Standard")(_.name)
    game.tournamentId.map { id =>
      s"${game.mode} $perf tournament https://lichess.org/tournament/$id"
    } orElse game.simulId.map { id =>
      s"$perf simul https://lichess.org/simul/$id"
    } getOrElse {
      s"${game.mode} $perf game"
    }
  }

  private def ratingDiffTag(p: Player, tag: Tag.type => TagType) =
    p.ratingDiff.map { rd => Tag(tag(Tag), s"${if (rd >= 0) "+" else ""}$rd") }

  def tags(
    game: Game,
    initialFen: Option[String],
    imported: Option[ParsedPgn]
  ): List[Tag] = gameLightUsers(game) match {
    case (wu, bu) =>
      val importedDate = imported.flatMap(_ tag "date")
      List[Option[Tag]](
        Tag(_.Event, imported.flatMap(_ tag "event") | { if (game.imported) "Import" else eventOf(game) }).some,
        Tag(_.Site, gameUrl(game.id)).some,
        Tag(_.Date, importedDate | Tag.UTCDate.format.print(game.createdAt)).some,
        Tag(_.Round, imported.flatMap(_ tag "round") | "-").some,
        Tag(_.White, player(game.whitePlayer, wu)).some,
        Tag(_.Black, player(game.blackPlayer, bu)).some,
        Tag(_.Result, result(game)).some,
        importedDate.isEmpty option Tag(_.UTCDate, imported.flatMap(_ tag "utcdate") | Tag.UTCDate.format.print(game.createdAt)),
        importedDate.isEmpty option Tag(_.UTCTime, imported.flatMap(_ tag "utctime") | Tag.UTCTime.format.print(game.createdAt)),
        Tag(_.WhiteElo, rating(game.whitePlayer)).some,
        Tag(_.BlackElo, rating(game.blackPlayer)).some,
        ratingDiffTag(game.whitePlayer, _.WhiteRatingDiff),
        ratingDiffTag(game.blackPlayer, _.BlackRatingDiff),
        wu.flatMap(_.title).map { t => Tag(_.WhiteTitle, t) },
        bu.flatMap(_.title).map { t => Tag(_.BlackTitle, t) },
        Tag(_.Variant, game.variant.name.capitalize).some,
        Tag(_.TimeControl, game.clock.fold("-") { c => s"${c.limit.roundSeconds}+${c.increment.roundSeconds}" }).some,
        Tag(_.ECO, game.opening.fold("?")(_.opening.eco)).some,
        Tag(_.Opening, game.opening.fold("?")(_.opening.name)).some,
        Tag(_.Termination, {
          import chess.Status._
          game.status match {
            case Created | Started => "Unterminated"
            case Aborted | NoStart => "Abandoned"
            case Timeout | Outoftime => "Time forfeit"
            case Resign | Draw | Stalemate | Mate | VariantEnd => "Normal"
            case Cheat => "Rules infraction"
            case UnknownFinish => "Unknown"
          }
        }).some
      ).flatten ::: customStartPosition(game.variant).??(List(
          Tag(_.FEN, initialFen | "?"),
          Tag("SetUp", "1")
        ))
  }

  private def makeTurns(moves: Seq[String], from: Int, clocks: Vector[Centis], startColor: Color): List[chessPgn.Turn] =
    (moves grouped 2).zipWithIndex.toList map {
      case (moves, index) =>
        val clockOffset = startColor.fold(0, 1)
        chessPgn.Turn(
          number = index + from,
          white = moves.headOption filter (".." !=) map { san =>
          chessPgn.Move(
            san = san,
            secondsLeft = clocks lift (index * 2 - clockOffset) map (_.roundSeconds)
          )
        },
          black = moves lift 1 map { san =>
          chessPgn.Move(
            san = san,
            secondsLeft = clocks lift (index * 2 + 1 - clockOffset) map (_.roundSeconds)
          )
        }
        )
    } filterNot (_.isEmpty)
}

object PgnDump {

  case class WithFlags(
    clocks: Boolean = true
  )

  def result(game: Game) =
    if (game.finished) game.winnerColor.fold("1/2-1/2")(_.fold("1-0", "0-1"))
    else "*"
}
