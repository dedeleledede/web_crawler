package com.dedeleledede;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage:");
            System.out.println("  view crawler-data");
            System.out.println("  crawl https://discord.com 2 8 crawler-data");
            System.out.println("  crawl-view https://discord.com 2 8 crawler-data");
            return;
        }

        String mode = args[0];

        if (mode.equals("crawl")) {
            String url = args[1];
            int depth = Integer.parseInt(args[2]);
            int threads = Integer.parseInt(args[3]);
            String folder = args.length >= 5 ? args[4] : "crawler-data";

            WebCrawler crawler = new WebCrawler(folder);
            crawler.crawlOnly(url, depth, threads);
            return;
        }

        if (mode.equals("crawl-view")) {
            String url = args[1];
            int depth = Integer.parseInt(args[2]);
            int threads = Integer.parseInt(args[3]);
            String folder = args.length >= 5 ? args[4] : "crawler-data";

            WebCrawler crawler = new WebCrawler(folder);
            crawler.crawlAndView(url, depth, threads);
            return;
        }

        if (mode.equals("view")) {
            String folder = args.length >= 2 ? args[1] : "crawler-data";

            WebCrawler crawler = new WebCrawler(folder);
            crawler.viewOnly();
        }
    }
}