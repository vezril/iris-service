package me.cference.iris.persistence

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import me.cference.iris.config.DbConfig

/** The pooled datasource. One pool, sized small: Iris is a single-writer service. */
object Db:

  def pool(cfg: DbConfig): HikariDataSource =
    val hc = new HikariConfig()
    hc.setJdbcUrl(cfg.jdbcUrl)
    hc.setUsername(cfg.user)
    hc.setPassword(cfg.password)
    hc.setMaximumPoolSize(4)
    hc.setPoolName("iris-db")
    new HikariDataSource(hc)
